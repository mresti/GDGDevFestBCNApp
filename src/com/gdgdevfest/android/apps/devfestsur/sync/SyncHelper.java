/*
 * Copyright 2012 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.gdgdevfest.android.apps.devfestsur.sync;

import static com.gdgdevfest.android.apps.devfestsur.util.LogUtils.LOGD;
import static com.gdgdevfest.android.apps.devfestsur.util.LogUtils.LOGI;
import static com.gdgdevfest.android.apps.devfestsur.util.LogUtils.makeLogTag;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import android.accounts.Account;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.Context;
import android.content.OperationApplicationException;
import android.content.SharedPreferences;
import android.content.SyncResult;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.preference.PreferenceManager;

import com.gdgdevfest.android.apps.devfestsur.R;
import com.gdgdevfest.android.apps.devfestsur.Config;
import com.gdgdevfest.android.apps.devfestsur.io.BlocksHandler;
import com.gdgdevfest.android.apps.devfestsur.io.JSONHandler;
import com.gdgdevfest.android.apps.devfestsur.io.MapPropertyHandler;
import com.gdgdevfest.android.apps.devfestsur.io.RoomsHandler;
import com.gdgdevfest.android.apps.devfestsur.io.SearchSuggestHandler;
import com.gdgdevfest.android.apps.devfestsur.io.SessionsHandler;
import com.gdgdevfest.android.apps.devfestsur.io.SpeakersHandler;
import com.gdgdevfest.android.apps.devfestsur.io.TracksHandler;
import com.gdgdevfest.android.apps.devfestsur.io.map.model.Tile;
import com.gdgdevfest.android.apps.devfestsur.provider.ScheduleContract;
import com.gdgdevfest.android.apps.devfestsur.util.AccountUtils;
import com.gdgdevfest.android.apps.devfestsur.util.Lists;
import com.gdgdevfest.android.apps.devfestsur.util.NetUtils;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.googleapis.services.CommonGoogleClientRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.googledevelopers.Googledevelopers;
import com.larvalabs.svgandroid.SVGParseException;
import com.sopinet.utils.SimpleContent;
import com.sopinet.utils.SimpleContent.ApiException;
import com.turbomanage.httpclient.BasicHttpClient;
import com.turbomanage.httpclient.ConsoleRequestLogger;
import com.turbomanage.httpclient.HttpResponse;
import com.turbomanage.httpclient.RequestLogger;

/**
 * A helper class for dealing with sync and other remote persistence operations.
 * All operations occur on the thread they're called from, so it's best to wrap
 * calls in an {@link android.os.AsyncTask}, or better yet, a
 * {@link android.app.Service}.
 */
public class SyncHelper {
    private static final String TAG = makeLogTag(SyncHelper.class);

    public static final int FLAG_SYNC_LOCAL = 0x1;

    private static final int LOCAL_VERSION_CURRENT = 61;
    private static final String LOCAL_MAPVERSION_CURRENT = "\"vlh7Ig\"";

    private Context mContext;

    public SyncHelper(Context context) {
        mContext = context;
    }

    public static void requestManualSync(Account mChosenAccount) {
        Bundle b = new Bundle();
        b.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        ContentResolver.requestSync(
                mChosenAccount,
                ScheduleContract.CONTENT_AUTHORITY, b);
    }

    /**
     * Loads conference information (sessions, rooms, tracks, speakers, etc.)
     * from a local static cache data and then syncs down data from the
     * Conference API.
     *
     * @param syncResult Optional {@link SyncResult} object to populate.
     * @throws IOException
     */
    public void performSync(SyncResult syncResult, int flags) throws IOException {

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        final int localVersion = prefs.getInt("local_data_version", 0);
        // Bulk of sync work, performed by executing several fetches from
        // local and online sources.
        final ContentResolver resolver = mContext.getContentResolver();
        ArrayList<ContentProviderOperation> batch = new ArrayList<ContentProviderOperation>();
        
        SimpleContent.prepareUserAgent(this.mContext);
        String str_speakers = null;
        String str_sessions = null;
        String str_map = null;
        String str_common = null;
        String str_rooms = null;
        String str_tracks = null;
        String str_session_tracks = null;
        try {
        	SimpleContent.clearCache();
			str_speakers = SimpleContent.getUrlContent("http://sur.gdgdevfest.com/_ponentes.json", this.mContext);
			str_sessions = SimpleContent.getUrlContent("http://sur.gdgdevfest.com/api/sessions.json", this.mContext);
			str_tracks = SimpleContent.getUrlContent("http://sur.gdgdevfest.com/api/tracks.json", this.mContext);
			str_session_tracks = SimpleContent.getUrlContent("http://sur.gdgdevfest.com/api/session_tracks.json", this.mContext);
			str_rooms = SimpleContent.getUrlContent("http://sur.gdgdevfest.com/api/rooms.json", this.mContext);
			str_map = SimpleContent.getUrlContent("http://sur.gdgdevfest.com/api/map.json", this.mContext);
			str_common = SimpleContent.getUrlContent("http://sur.gdgdevfest.com/api/common.json", this.mContext);
		} catch (ApiException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}

        LOGI(TAG, "Performing sync");

            final long startLocal = System.currentTimeMillis();
            final boolean localParse = localVersion < LOCAL_VERSION_CURRENT;
            LOGD(TAG, "found localVersion=" + localVersion + " and LOCAL_VERSION_CURRENT="
                    + LOCAL_VERSION_CURRENT);
            // Only run local sync if there's a newer version of data available
            // than what was last locally-sync'd.
            if (true) {
            	//int json_rooms = R.raw.rooms_es;
            	//int json_common_slots = R.raw.common_slots_es;
            	//int json_tracks = R.raw.tracks_es;
            	//int json_speakers = R.raw.speakers_es;
            	//int json_sessions = R.raw.sessions_es;
            	//int json_session_tracks = R.raw.session_tracks_es;
            	int json_search_suggest = R.raw.search_suggest_es;
            	//int json_map = R.raw.map_es;
            	try{
                    Class res = R.raw.class;
                    //json_rooms = res.getField(mContext.getResources().getString(R.string.json_rooms)).getInt(null);
                    //json_common_slots = res.getField(mContext.getResources().getString(R.string.json_common_slots)).getInt(null);
                    //json_tracks = res.getField(mContext.getResources().getString(R.string.json_tracks)).getInt(null);
                    //json_speakers = res.getField(mContext.getResources().getString(R.string.json_speakers)).getInt(null);
                    //json_sessions = res.getField(mContext.getResources().getString(R.string.json_sessions)).getInt(null);
                    //json_session_tracks = res.getField(mContext.getResources().getString(R.string.json_session_tracks)).getInt(null);
                    json_search_suggest = res.getField(mContext.getResources().getString(R.string.json_search_suggest)).getInt(null);
                    //json_map = res.getField(mContext.getResources().getString(R.string.json_map)).getInt(null);
            	} catch (Exception e){
            		LOGI(TAG, "Error al recuperar los raws localizados: "+e.getMessage());
            	}
            	
                // Load static local data
                LOGI(TAG, "Local syncing rooms");
                batch.addAll(new RoomsHandler(mContext).parse(str_rooms));
                LOGI(TAG, "Local syncing blocks");
                batch.addAll(new BlocksHandler(mContext).parse(str_common));
                LOGI(TAG, "Local syncing tracks");
                batch.addAll(new TracksHandler(mContext).parse(str_tracks));
                LOGI(TAG, "Local syncing speakers");
                batch.addAll(new SpeakersHandler(mContext).parseString(
                		str_speakers));
                LOGI(TAG, "Local syncing sessions");
                batch.addAll(new SessionsHandler(mContext).parseString(str_sessions,str_session_tracks));
                LOGI(TAG, "Local syncing search suggestions");
                batch.addAll(new SearchSuggestHandler(mContext).parse(
                        JSONHandler.parseResource(mContext, json_search_suggest)));
                LOGI(TAG, "Local syncing map");
                MapPropertyHandler mapHandler = new MapPropertyHandler(mContext);
                batch.addAll(mapHandler.parse(str_map));
                //need to sync tile files before data is updated in content provider
                syncMapTiles(mapHandler.getTiles());

                prefs.edit().putInt("local_data_version", LOCAL_VERSION_CURRENT).commit();
                prefs.edit().putString("local_mapdata_version", LOCAL_MAPVERSION_CURRENT).commit();
                if (syncResult != null) {
                    ++syncResult.stats.numUpdates; // TODO: better way of indicating progress?
                    ++syncResult.stats.numEntries;
                }
            }

            LOGD(TAG, "Local sync took " + (System.currentTimeMillis() - startLocal) + "ms");

            try {
                // Apply all queued up batch operations for local data.
                resolver.applyBatch(ScheduleContract.CONTENT_AUTHORITY, batch);
            } catch (RemoteException e) {
                throw new RuntimeException("Problem applying batch operation", e);
            } catch (OperationApplicationException e) {
                throw new RuntimeException("Problem applying batch operation", e);
            }

            batch = new ArrayList<ContentProviderOperation>();
        }

    public void addOrRemoveSessionFromSchedule(Context context, String sessionId,
            boolean inSchedule) throws IOException {
        LOGI(TAG, "Updating session on user schedule: " + sessionId);
        Googledevelopers conferenceAPI = getConferenceAPIClient();
        try {
            sendScheduleUpdate(conferenceAPI, context, sessionId, inSchedule);
        } catch (GoogleJsonResponseException e) {
            if (e.getDetails().getCode() == 401) {
                LOGI(TAG, "Unauthorized; getting a new auth token.", e);
                AccountUtils.refreshAuthToken(mContext);
                // Try request one more time with new credentials before giving up
                conferenceAPI = getConferenceAPIClient();
                sendScheduleUpdate(conferenceAPI, context, sessionId, inSchedule);
            }
        }
    }

    private void sendScheduleUpdate(Googledevelopers conferenceAPI,
            Context context, String sessionId, boolean inSchedule) throws IOException {
        if (inSchedule) {
            conferenceAPI.users().events().sessions().update(Config.EVENT_ID, sessionId, null).execute();
        } else {
            conferenceAPI.users().events().sessions().delete(Config.EVENT_ID, sessionId).execute();
        }
    }

    private ArrayList<ContentProviderOperation> remoteSyncMapData(String urlString,
            SharedPreferences preferences) throws IOException {
        final String localVersion = preferences.getString("local_mapdata_version", null);

        ArrayList<ContentProviderOperation> batch = Lists.newArrayList();

        BasicHttpClient httpClient = new BasicHttpClient();
        httpClient.setRequestLogger(mQuietLogger);
        httpClient.addHeader("If-None-Match", localVersion);

        LOGD(TAG,"Local map version: "+localVersion);
        HttpResponse response = httpClient.get(urlString, null);
        final int status = response.getStatus();

        if (status == HttpURLConnection.HTTP_OK) {
            // Data has been updated, otherwise would have received HTTP_NOT_MODIFIED
            LOGI(TAG, "Remote syncing map data");
            final List<String> etag = response.getHeaders().get("ETag");
            if (etag != null && etag.size() > 0) {
                MapPropertyHandler handler = new MapPropertyHandler(mContext);
                batch.addAll(handler.parse(response.getBodyAsString()));
                syncMapTiles(handler.getTiles());

                // save new etag as version
                preferences.edit().putString("local_mapdata_version", etag.get(0)).commit();
            }
        } //else: no update

        return batch;
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        return cm.getActiveNetworkInfo() != null &&
                cm.getActiveNetworkInfo().isConnectedOrConnecting();
    }

    /**
     * Synchronise the map overlay files either from the local assets (if available) or from a remote url.
     *
     * @param collection Set of tiles containing a local filename and remote url.
     * @throws IOException
     */
    private void syncMapTiles(Collection<Tile> collection) throws IOException, SVGParseException {
    }
  
    /**
     * Write the byte array directly to a file.
     * @throws IOException
     */
    private void writeFile(byte[] data, File file) throws IOException {
        BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(file, false));
        bos.write(data);
        bos.close();
    }

    /**
     * A type of ConsoleRequestLogger that does not log requests and responses.
     */
    private RequestLogger mQuietLogger = new ConsoleRequestLogger(){
        @Override
        public void logRequest(HttpURLConnection uc, Object content) throws IOException { }

        @Override
        public void logResponse(HttpResponse res) { }
    };

    private Googledevelopers getConferenceAPIClient() {
        HttpTransport httpTransport = new NetHttpTransport();
        JsonFactory jsonFactory = new GsonFactory();
        GoogleCredential credential =
                new GoogleCredential().setAccessToken(AccountUtils.getAuthToken(mContext));
        // Note: The Googledevelopers API is unique, in that it requires an API key in addition to the client
        //       ID normally embedded an an OAuth token. Most apps will use one or the other.
        return new Googledevelopers.Builder(httpTransport, jsonFactory, null)
                .setApplicationName(NetUtils.getUserAgent(mContext))
                .setGoogleClientRequestInitializer(new
                        CommonGoogleClientRequestInitializer(Config.API_KEY))
                .setHttpRequestInitializer(credential)
                .build();
    }
}
