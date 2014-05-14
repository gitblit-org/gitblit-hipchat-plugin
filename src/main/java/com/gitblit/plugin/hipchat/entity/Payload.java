/**
 * Copyright (C) 2014 gitblit.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gitblit.plugin.hipchat.entity;

import com.google.gson.annotations.SerializedName;

public class Payload {

	public static enum MessageFormat {
		html, text;
	}

	public static enum Color {
		yellow, red, green, purple, gray, random
	}

	private Color color;
	private String message;
	boolean notify;
	@SerializedName("message_format")
	private MessageFormat messageFormat;

	private transient String room;

	Payload() {
	}

	public Payload(String message) {
		this.message = message;
		this.messageFormat = MessageFormat.text;
	}

	public static Payload text(String message) {
		return new Payload(message);
	}

	public static Payload html(String message) {
		Payload payload = new Payload(message);
		payload.messageFormat = MessageFormat.html;
		return payload;
	}

	public Payload message(String message) {
		setMessage(message);
		return this;
	}

	public Payload messageFormat(MessageFormat messageFormat) {
		setMessageFormat(messageFormat);
		return this;
	}

	public Payload color(Color color) {
		setColor(color);
		return this;
	}

	public Payload room(String room) {
		setRoom(room);
		return this;
	}


	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public void setMessage(MessageFormat message) {
		this.messageFormat = message;
	}

	public MessageFormat getMessageFormat() {
		return messageFormat;
	}

	public void setMessageFormat(MessageFormat messageFormat) {
		this.messageFormat = messageFormat;
	}

	public Color getColor() {
		return color;
	}

	public void setColor(Color color) {
		this.color = color;
	}

	public String getRoom() {
		return room;
	}

	public void setRoom(String room) {
		this.room = room;
	}
}
