/**
 * This file is part of Simple Last.fm Scrobbler.
 * <p/>
 * https://github.com/tgwizard/sls
 * <p/>
 * Copyright 2011 Simple Last.fm Scrobbler Team
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.adam.aslfms.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.adam.aslfms.R;
import com.adam.aslfms.SettingsActivity;
import com.adam.aslfms.service.Handshaker.HandshakeResult;
import com.adam.aslfms.util.AppSettings;
import com.adam.aslfms.util.AuthStatus;
import com.adam.aslfms.util.MD5;
import com.adam.aslfms.util.ScrobblesDatabase;
import com.adam.aslfms.util.Track;
import com.adam.aslfms.util.AuthStatus.BadSessionException;
import com.adam.aslfms.util.AuthStatus.TemporaryFailureException;
import com.adam.aslfms.util.Util;
import com.adam.aslfms.util.enums.SubmissionType;

/**
 * @author tgwizard
 */
public class Scrobbler extends AbstractSubmitter {

    private static final String TAG = "Scrobbler";

    // private final Context mCtx;
    private final ScrobblesDatabase mDb;

    private final Context mCtx;

    private final AppSettings settings;

    public static final int MAX_SCROBBLE_LIMIT = 50;

    public Scrobbler(NetApp napp, Context ctx, Networker net,
                     ScrobblesDatabase db) {
        super(napp, ctx, net);
        this.mDb = db;
        this.mCtx = ctx;
        this.settings = new AppSettings(ctx);
    }

    @Override
    public boolean doRun(HandshakeResult hInfo) {
        boolean ret;
        try {
            Log.d(TAG, "Scrobbling: " + getNetApp().getName());
            Track[] tracks = mDb.fetchTracksArray(getNetApp(), MAX_SCROBBLE_LIMIT);

            if (tracks.length == 0) {
                Log.d(TAG, "Retrieved 0 tracks from db, no scrobbling: " + getNetApp().getName());
                return true;
            }
            Log.d(TAG, "Retrieved " + tracks.length + " tracks from db: " + getNetApp().getName());

            for (Track track : tracks) {
                Log.d(TAG, getNetApp().getName() + ": " + track.toString());
            }

            scrobbleCommit(hInfo, tracks); // throws if unsuccessful

            // delete scrobbles (not tracks) from db (not array)
            for (Track track : tracks) {
                mDb.deleteScrobble(getNetApp(), track.getRowId());
            }

            // clean up tracks if no one else wants to scrobble them
            mDb.cleanUpTracks();

            // there might be more tracks in the db
            if (tracks.length == MAX_SCROBBLE_LIMIT) {
                Log.d(TAG, "Relaunching scrobbler, might be more tracks in db");
                relaunchThis();
            }

            // status stuff
            notifySubmissionStatusSuccessful(tracks[tracks.length - 1],
                    tracks.length);

            ret = true;
        } catch (BadSessionException e) {
            Log.i(TAG, "BadSession: " + e.getMessage() + ": "
                    + getNetApp().getName());
            settings.setSessionKey(NetApp.LASTFM, "");
            getNetworker().launchHandshaker();
            relaunchThis();
            notifySubmissionStatusFailure(getContext().getString(
                    R.string.auth_just_error));
            e.getStackTrace();
            ret = true;
        } catch (TemporaryFailureException e) {
            Log.i(TAG, "Tempfail: " + e.getMessage() + ": "
                    + getNetApp().getName());
            notifySubmissionStatusFailure(getContext().getString(
                    R.string.auth_network_error_retrying));
            e.getStackTrace();
            ret = false;
        } catch (AuthStatus.ClientBannedException e) {
            Log.e(TAG, "This version of the client has been banned!!" + ": "
                    + getNetApp().getName());
            Log.e(TAG, e.getMessage());
            // TODO: what??  notify user
            notifyAuthStatusUpdate(AuthStatus.AUTHSTATUS_CLIENTBANNED);
            Util.myNotify(mCtx, SettingsActivity.class, getNetApp().getName(),
                    mCtx.getString(R.string.auth_client_banned));
            e.getStackTrace();
            ret = true;
        }
        return ret;
    }

    @Override
    protected void relaunchThis() {
        getNetworker().launchScrobbler();
    }

    private void notifySubmissionStatusFailure(String reason) {
        super.notifySubmissionStatusFailure(SubmissionType.SCROBBLE, reason);
    }

    private void notifySubmissionStatusSuccessful(Track track, int statsInc) {
        super.notifySubmissionStatusSuccessful(SubmissionType.SCROBBLE, track,
                statsInc);
    }

    private void notifyAuthStatusUpdate(int st) {
        settings.setAuthStatus(getNetApp(), st);
        Intent i = new Intent(ScrobblingService.BROADCAST_ONAUTHCHANGED);
        i.putExtra("netapp", getNetApp().getIntentExtraValue());
        getContext().sendBroadcast(i);
    }

    /**
     * @return a {@link ScrobbleResult} struct with some info
     * @throws BadSessionException
     * @throws TemporaryFailureException
     */
    public void scrobbleCommit(HandshakeResult hInfo, Track[] tracks)
            throws BadSessionException, TemporaryFailureException, AuthStatus.ClientBannedException {

        URL url;
        HttpURLConnection conn = null;

// handle Exception
        if (getNetApp() == NetApp.LASTFM && !settings.getSessionKey(getNetApp()).equals("")) {         // start of API 2.0 usage.
            try {

                url = new URL("http://ws.audioscrobbler.com/2.0/");

                Map<String, Object> params = new TreeMap<>();
                String sign = "";
                params.put("method", "track.scrobble");
                params.put("api_key", settings.rcnvK(settings.getAPIkey()));
                params.put("sk", settings.getSessionKey(NetApp.LASTFM));
                for (int i = 0; i < tracks.length; i++) {
                    Track track = tracks[i];
                    String is = "[" + i + "]";

                    if (track.getAlbum() != null) {
                        params.put("album" + is, track.getAlbum());
                    }
                    params.put("artist" + is, track.getArtist());
                    if (track.getSource().equals("R") || track.getSource().equals("E")) {
                        params.put("chosenByUser" + is, 0);
                    }
                    if (track.getDuration()!= -1) {
                        params.put("duration" + is, Integer.toString(track.getDuration()));
                    }
                    if (track.getMbid() != null) {
                        params.put("mbid" + is, track.getMbid());
                    }
                    params.put("timestamp" + is, track.getWhen());
                    params.put("track" + is, track.getTrack());
                    if (track.getTrackNr() != null) {
                        params.put("trackNumber" + is, track.getTrackNr());
                    }


                    if (track.getRating().equals("L")) {
                        NetworkerManager mNetManager = new NetworkerManager(mCtx, mDb);
                        mNetManager.launchHeartTrack(track);
                    }
                }
                for (Map.Entry<String, Object> param : params.entrySet()) {
                    sign += param.getKey() + String.valueOf(param.getValue());
                }
                String signature = MD5.getHashString(sign + settings.rcnvK(settings.getSecret()));
                params.put("api_sig", signature);


                StringBuilder postData = new StringBuilder();
                for (Map.Entry<String, Object> param : params.entrySet()) {
                    if (postData.length() != 0) postData.append('&');
                    postData.append(URLEncoder.encode(param.getKey(), "UTF-8"));
                    postData.append('=');
                    postData.append(URLEncoder.encode(String.valueOf(param.getValue()), "UTF-8"));
                }
                byte[] postDataBytes = postData.toString().getBytes("UTF-8");

                conn = (HttpURLConnection) url.openConnection();
                // Log.d(TAG,conn.toString());
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setRequestProperty("Content-Length", String.valueOf(postDataBytes.length));
                conn.setDoOutput(true);
                conn.setDoInput(true);
                conn.getOutputStream().write(postDataBytes);
                Log.i(TAG, params.toString());
                int resCode = conn.getResponseCode();
                Log.d(TAG, "Response code: " + resCode);
                BufferedReader r;
                if (resCode == 200) {
                    r = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                } else {
                    r = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
                }
                StringBuilder rsponse = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    rsponse.append(line).append('\n');
                }
                r.close();
                String response = rsponse.toString();
                // some redundancy here ?
                String[] lines = response.split("\n");

              //  Log.d(TAG, "Scrobble Result: " + lines.length + " : " + response.contains("status=\"ok\"") + " : " + lines[1]);
                if (response.contains("status=\"ok\"")) {
                    Log.i(TAG, "Scrobbling success: " + getNetApp().getName());
                } else {
                    if (response.contains("code=\"26\"") || response.contains("code=\"10\"")) {
                        Log.e(TAG, "Scobble failed: client banned: " + NetApp.LASTFM);
                        settings.setSessionKey(NetApp.LASTFM, "");
                        throw new AuthStatus.ClientBannedException("Now Playing failed because of client banned");
                    } else if (response.contains("code=\"4\"") || response.contains("code=\"9\"")) {
                        Log.i(TAG, "Scrobble failed: bad auth: " + NetApp.LASTFM);
                        settings.setSessionKey(NetApp.LASTFM, "");
                        throw new BadSessionException("Now Playing failed because of badsession");
                    } else {
                        String reason = lines[2].substring(7);
                        Log.e(TAG, "Scrobble fails: FAILED " + reason + ": " + NetApp.LASTFM);
                        //settings.setSessionKey(NetApp.LASTFM, "");
                        throw new TemporaryFailureException("Now playing failed because of " + response);
                    }
                }
            } catch (IOException e) {
                throw new TemporaryFailureException("Scrobble failed weirdly: " + e.getMessage());
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }

        } else if (getNetApp() == NetApp.LIBREFM) {
            try {
                url = new URL(hInfo.scrobbleUri);
                Log.d(TAG, url.toString());

                Map<String, Object> params = new LinkedHashMap<>();
                params.put("s", hInfo.sessionId);
                for (int i = 0; i < tracks.length; i++) {
                    Track track = tracks[i];
                    String is = "[" + i + "]";
                    params.put("a" + is, track.getArtist());
                    params.put("b" + is, track.getAlbum());
                    params.put("t" + is, track.getTrack());
                    params.put("i" + is, Long.toString(track
                            .getWhen()));
                    params.put("o" + is, track.getSource());
                    params.put("l" + is, Integer.toString(track
                            .getDuration()));
                    params.put("n" + is, track.getTrackNr());
                    params.put("m" + is, track.getMbid());
                    params.put("r" + is, track.getRating());
                }
                StringBuilder postData = new StringBuilder();
                for (Map.Entry<String, Object> param : params.entrySet()) {
                    if (postData.length() != 0) postData.append('&');
                    postData.append(URLEncoder.encode(param.getKey(), "UTF-8"));
                    postData.append('=');
                    if (param.getValue() == null) {
                        param.setValue("");
                    } else {
                        postData.append(URLEncoder.encode(String.valueOf(param.getValue()), "UTF-8"));
                    }
                }
                byte[] postDataBytes = postData.toString().getBytes("UTF-8");

                conn = (HttpURLConnection) url.openConnection();
                // Log.d(TAG,conn.toString());
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setRequestProperty("Content-Length", String.valueOf(postDataBytes.length));
                conn.setDoOutput(true);
                conn.setDoInput(true);
                conn.getOutputStream().write(postDataBytes);
                Log.i(TAG, params.toString());
                int resCode = conn.getResponseCode();
                Log.d(TAG, "Response code: " + resCode);
                BufferedReader r;
                if (resCode == 200) {
                    r = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                } else {
                    r = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
                }
                StringBuilder rsponse = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    rsponse.append(line).append('\n');
                }
                r.close();
                String response = rsponse.toString();
                // some redundancy here ?
                String[] lines = response.split("\n");
                //Log.d(TAG, "Scrobbler Result: " + lines.length + " : " + response);
                if (response.startsWith("OK")) {
                    Log.i(TAG, "Scrobbler success: " + getNetApp().getName());
                } else if (response.startsWith("BADSESSION")) {
                    throw new BadSessionException("Scrobble failed because of badsession");
                } else if (response.startsWith("FAILED")) {
                    String reason = lines[0].substring(7);
                    throw new TemporaryFailureException("Scrobble failed: " + reason);
                } else {
                    throw new TemporaryFailureException("Scrobble failed weirdly: " + response);
                }

            } catch (IOException e) {
                throw new TemporaryFailureException(TAG + ": " + e.getMessage());
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
    }
}
