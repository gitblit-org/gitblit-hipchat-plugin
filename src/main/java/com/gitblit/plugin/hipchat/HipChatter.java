/*
 * Copyright 2014 gitblit.com.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gitblit.plugin.hipchat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.params.AllClientPNames;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.CoreProtocolPNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gitblit.Constants;
import com.gitblit.manager.IManager;
import com.gitblit.manager.IRuntimeManager;
import com.gitblit.models.RepositoryModel;
import com.gitblit.plugin.hipchat.entity.Payload;
import com.gitblit.utils.StringUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Configures the final payload and sends a HipChat message.
 *
 * @author James Moger
 *
 */
public class HipChatter implements IManager {

	private static HipChatter instance;

	final Logger log = LoggerFactory.getLogger(getClass());

	final IRuntimeManager runtimeManager;

	final ExecutorService taskPool;

	public static void init(IRuntimeManager manager) {
		if (instance == null) {
			instance = new HipChatter(manager);
		}
	}

	public static HipChatter instance() {
		return instance;
	}

	HipChatter(IRuntimeManager runtimeManager) {
		this.runtimeManager = runtimeManager;
		this.taskPool = Executors.newCachedThreadPool();
	}

	@Override
	public HipChatter start() {
		return this;
	}

	@Override
	public HipChatter stop() {
		this.taskPool.shutdown();
		return this;
	}

	/**
	 * Returns true if the repository can be posted to HipChat.
	 *
	 * @param repository
	 * @return true if the repository can be posted to HipChat
	 */
	public boolean shallPost(RepositoryModel repository) {
		boolean postPersonalRepos = runtimeManager.getSettings().getBoolean(Plugin.SETTING_POST_PERSONAL_REPOS, false);
		if (repository.isPersonalRepository() && !postPersonalRepos) {
			return false;
		}
		return true;
	}

	/**
	 * Optionally sets the room of the payload based on the repository.
	 *
	 * @param repository
	 * @param payload
	 */
	public void setRoom(RepositoryModel repository, Payload payload) {
		boolean useProjectChannels = runtimeManager.getSettings().getBoolean(Plugin.SETTING_USE_PROJECT_ROOMS, false);
		if (!useProjectChannels) {
			return;
		}

		if (StringUtils.isEmpty(repository.projectPath)) {
			return;
		}

		String defaultRoom = runtimeManager.getSettings().getString(Plugin.SETTING_DEFAULT_ROOM, null);
		if (!StringUtils.isEmpty(defaultRoom)) {
			payload.setRoom(defaultRoom + "-" + repository.projectPath);
		} else {
			payload.setRoom(repository.projectPath);
		}
	}

	/**
	 * Asynchronously send a simple text message.
	 *
	 * @param message
	 * @throws IOException
	 */
	public void sendAsync(String message) {
		sendAsync(new Payload(message));
	}

	/**
	 * Asynchronously send a payload message.
	 *
	 * @param payload
	 * @throws IOException
	 */
	public void sendAsync(final Payload payload) {
		taskPool.submit(new HipChatterTask(this, payload));
	}

	/**
	 * Send a simple text message.
	 *
	 * @param message
	 * @throws IOException
	 */
	public void send(String message) throws IOException  {
		send(new Payload(message));
	}

	/**
	 * Send a payload message.
	 *
	 * @param payload
	 * @throws IOException
	 */
	public void send(Payload payload) throws IOException {

		String room = payload.getRoom();
		String token;

		if (StringUtils.isEmpty(room)) {
			// default room
			room = runtimeManager.getSettings().getString(Plugin.SETTING_DEFAULT_ROOM, null);
			token = runtimeManager.getSettings().getString(Plugin.SETTING_DEFAULT_TOKEN, null);
		} else {
			// specified room, validate token
			token = runtimeManager.getSettings().getString(String.format(Plugin.SETTING_ROOM_TOKEN, room), null);
			if (StringUtils.isEmpty(token)) {
				room = runtimeManager.getSettings().getString(Plugin.SETTING_DEFAULT_ROOM, null);
				token = runtimeManager.getSettings().getString(Plugin.SETTING_DEFAULT_TOKEN, null);
				log.warn("No HipChat API token specified for '{}', defaulting to '{}'", payload.getRoom(), room);
				log.warn("Please set '{} = TOKEN' in gitblit.properties", String.format(Plugin.SETTING_ROOM_TOKEN, room));
			}
		}

		String hipchatUrl = String.format("https://api.hipchat.com/v2/room/%s/notification?auth_token=%s", room, token);

		Gson gson = new GsonBuilder().create();

		HttpClient client = new DefaultHttpClient();
		HttpPost post = new HttpPost(hipchatUrl);
		post.getParams().setParameter(CoreProtocolPNames.USER_AGENT, Constants.NAME + "/" + Constants.getVersion());
		post.getParams().setParameter(CoreProtocolPNames.HTTP_CONTENT_CHARSET, "UTF-8");

		client.getParams().setParameter(AllClientPNames.CONNECTION_TIMEOUT, 5000);
		client.getParams().setParameter(AllClientPNames.SO_TIMEOUT, 5000);

		String body = gson.toJson(payload);
		StringEntity entity = new StringEntity(body, "UTF-8");
		entity.setContentType("application/json");
		post.setEntity(entity);

		HttpResponse response = client.execute(post);
		int rc = response.getStatusLine().getStatusCode();

		if (HttpStatus.SC_NO_CONTENT == rc) {
			// This is the expected result code
			// https://www.hipchat.com/docs/apiv2/method/send_room_notification
			// replace this with post.closeConnection() after JGit updates to HttpClient 4.2
			post.abort();
		} else {
			String result = null;
			InputStream is = response.getEntity().getContent();
			try {
				byte [] buffer = new byte[8192];
				ByteArrayOutputStream os = new ByteArrayOutputStream();
				int len = 0;
				while ((len = is.read(buffer)) > -1) {
					os.write(buffer, 0, len);
				}
				result = os.toString("UTF-8");
			} finally {
				if (is != null) {
					is.close();
				}
			}

			log.error("HipChat plugin sent:");
			log.error(body);
			log.error("HipChat returned:");
			log.error(result);
		}
	}

	private static class HipChatterTask implements Serializable, Callable<Boolean> {

		private static final long serialVersionUID = 1L;

		final Logger log = LoggerFactory.getLogger(getClass());
		final HipChatter hipChatter;
		final Payload payload;

		public HipChatterTask(HipChatter slacker, Payload payload) {
			this.hipChatter = slacker;
			this.payload = payload;
		}

		@Override
		public Boolean call() {
			try {
				hipChatter.send(payload);
				return true;
			} catch (IOException e) {
				log.error("Failed to send asynchronously to HipChat!", e);
			}
			return false;
		}
	}
}
