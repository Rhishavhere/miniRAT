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
    res.send(`
        <!DOCTYPE html>
        <html>
        <head>
            <title>Gallery Viewer</title>
            <style>
                body { font-family: Arial, sans-serif; margin: 20px; }
                .gallery { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 15px; }
                .thumbnail { border: 1px solid #ddd; border-radius: 5px; overflow: hidden; }
                .thumbnail img { width: 100%; height: auto; display: block; }
                .filename { font-size: 12px; text-align: center; padding: 5px; }
                .loading { text-align: center; padding: 20px; }
                .error { color: red; text-align: center; }
            </style>
        </head>
        <body>
            <h1>Gallery Viewer</h1>
            <div id="gallery" class="gallery">
                <div class="loading">Loading thumbnails...</div>
            </div>

            <script>
                async function loadThumbnails() {
                    try {
                        const response = await fetch('/api/thumbnails');
                        const data = await response.json();

                        const gallery = document.getElementById('gallery');
                        gallery.innerHTML = '';

                        if (data.thumbnails && data.thumbnails.length > 0) {
                            data.thumbnails.forEach(thumb => {
                                const div = document.createElement('div');
                                div.className = 'thumbnail';
                                div.innerHTML = \`
                                    <img src="/uploads/\${thumb.thumbnail}" alt="\${thumb.name}">
                                    <div class="filename">\${thumb.name.substring(0, 20)}...</div>
                                \`;
                                gallery.appendChild(div);
                            });
                        } else {
                            gallery.innerHTML = '<div class="error">No thumbnails found</div>';
                        }
                    } catch (error) {
                        console.error('Error loading thumbnails:', error);
                        document.getElementById('gallery').innerHTML =
                            '<div class="error">Failed to load thumbnails</div>';
                    }
                }

                // Load thumbnails when page loads
                loadThumbnails();

                // Refresh every 30 seconds
                setInterval(loadThumbnails, 30000);
            </script>
        </body>
        </html>
    `);
});

app.listen(port, '0.0.0.0', () => {
    console.log(`RAT server running at http://localhost:${port}`);
});

// Handle server shutdown
process.on('SIGINT', () => {
    console.log('Shutting down server...');
    process.exit(0);
});
