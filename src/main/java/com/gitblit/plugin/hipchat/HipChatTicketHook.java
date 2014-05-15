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
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ro.fortsoft.pf4j.Extension;

import com.gitblit.Constants;
import com.gitblit.IStoredSettings;
import com.gitblit.Keys;
import com.gitblit.extensions.TicketHook;
import com.gitblit.manager.IGitblit;
import com.gitblit.manager.IRepositoryManager;
import com.gitblit.manager.IRuntimeManager;
import com.gitblit.manager.IUserManager;
import com.gitblit.models.RepositoryModel;
import com.gitblit.models.TicketModel;
import com.gitblit.models.TicketModel.Change;
import com.gitblit.models.TicketModel.Patchset;
import com.gitblit.models.UserModel;
import com.gitblit.plugin.hipchat.entity.Payload;
import com.gitblit.plugin.hipchat.entity.Payload.Color;
import com.gitblit.servlet.GitblitContext;
import com.gitblit.utils.ActivityUtils;
import com.gitblit.utils.BugtraqProcessor;
import com.gitblit.utils.MarkdownUtils;
import com.gitblit.utils.StringUtils;

/**
 * This hook will post a message to a room when a ticket is created or updated.
 *
 * @author James Moger
 *
 */
@Extension
public class HipChatTicketHook extends TicketHook {

	final String name = getClass().getSimpleName();

	final Logger log = LoggerFactory.getLogger(getClass());

	final HipChatter hipChatter;

	final IStoredSettings settings;

	public HipChatTicketHook() {
		super();

		IRuntimeManager runtimeManager = GitblitContext.getManager(IRuntimeManager.class);
		HipChatter.init(runtimeManager);
    	hipChatter = HipChatter.instance();
    	settings = runtimeManager.getSettings();
	}

    @Override
    public void onNewTicket(TicketModel ticket) {
    	if (!shallPost(ticket)) {
			return;
		}

		Set<TicketModel.Field> fieldExclusions = new HashSet<TicketModel.Field>();
		fieldExclusions.addAll(Arrays.asList(TicketModel.Field.watchers, TicketModel.Field.voters,
				TicketModel.Field.status, TicketModel.Field.mentions));

    	Change change = ticket.changes.get(0);
    	IUserManager userManager = GitblitContext.getManager(IUserManager.class);

    	UserModel reporter = userManager.getUserModel(change.author);
    	StringBuilder sb = new StringBuilder();
    	sb.append(String.format("<b>%s</b> has created <b>%s</b> <a href=\"%s\">ticket-%s</a>", reporter.getDisplayName(),
    			StringUtils.stripDotGit(ticket.repository), getUrl(ticket), ticket.number));

    	fields(sb, ticket, ticket.changes.get(0), fieldExclusions);

    	Payload payload = Payload.html(sb.toString());
    	payload.color(Color.purple);

   		hipChatter.sendAsync(payload);
    }

    @Override
    public void onUpdateTicket(TicketModel ticket, Change change) {
    	if (!shallPost(ticket)) {
			return;
		}
		Set<TicketModel.Field> fieldExclusions = new HashSet<TicketModel.Field>();
		fieldExclusions.addAll(Arrays.asList(TicketModel.Field.watchers, TicketModel.Field.voters,
				TicketModel.Field.mentions, TicketModel.Field.title, TicketModel.Field.body,
				TicketModel.Field.mergeSha));

		IUserManager userManager = GitblitContext.getManager(IUserManager.class);
		String author = "<b>" + userManager.getUserModel(change.author).getDisplayName() + "</b>";
		String url = String.format("<a href=\"%s\">ticket-%s</a>", getUrl(ticket), ticket.number);
		String repo = "<b>" + StringUtils.stripDotGit(ticket.repository) + "</b>";
		String msg = null;
		String emoji = null;

		if (change.hasReview()) {
			/*
			 * Patchset review
			 */
    		msg = String.format("%s has reviewed %s %s patchset %s-%s", author, repo, url,
    				change.patchset.number, change.patchset.rev);
    		switch (change.review.score) {
    		case approved:
    			emoji = ":white_check_mark:";
    			break;
    		case looks_good:
    			emoji = "(thumbsup)";
    			break;
    		case needs_improvement:
    			emoji = "(thumbsdown)";
    			break;
    		case vetoed:
    			emoji = ":no_entry_sign:";
    			break;
    		default:
    			break;
    		}
		} else if (change.hasPatchset()) {
			/*
			 * New Patchset
			 */
			String tip = change.patchset.tip;
			String base;
			String leadIn;
			if (change.patchset.rev == 1) {
				if (change.patchset.number == 1) {
					/*
					 * Initial proposal
					 */
					leadIn = String.format("%s has pushed a proposal for %s %s", author, repo, url);
				} else {
					/*
					 * Rewritten patchset
					 */
					leadIn = String.format("%s has rewritten the patchset for %s %s (%s)", author, repo, url, change.patchset.type);
				}
				base = change.patchset.base;
			} else {
				/*
				 * Fast-forward patchset update
				 */
				leadIn = String.format("%s has added %s %s to %s %s", author, change.patchset.added,
						change.patchset.added == 1 ? "commit" : "commits", repo, url);
				Patchset prev = ticket.getPatchset(change.patchset.number, change.patchset.rev - 1);
				base = prev.tip;
			}

			StringBuilder sb = new StringBuilder();
			sb.append(leadIn);

			// abbreviated commit list
			List<RevCommit> commits = getCommits(ticket.repository, base, tip);
			sb.append("\n<table><tbody>\n");
			int shortIdLen = settings.getInteger(Keys.web.shortCommitIdLength, 6);
			int maxCommits = 5;
			for (int i = 0; i < Math.min(maxCommits, commits.size()); i++) {
				RevCommit commit = commits.get(i);
				String username = "";
				String email = "";
				if (commit.getAuthorIdent().getEmailAddress() != null) {
					username = commit.getAuthorIdent().getName();
					email = commit.getAuthorIdent().getEmailAddress().toLowerCase();
					if (StringUtils.isEmpty(username)) {
						username = email;
					}
				} else {
					username = commit.getAuthorIdent().getName();
					email = username.toLowerCase();
				}
				String gravatarUrl = ActivityUtils.getGravatarThumbnailUrl(email, 16);
				String commitUrl = getUrl(ticket.repository, null, commit.getName());
				String shortId = commit.getName().substring(0, shortIdLen);
				String shortMessage = StringUtils.trimString(commit.getShortMessage(), Constants.LEN_SHORTLOG);
				String row = String.format("<tr><td><img src=\"%s\"/></td><td><pre><a href=\"%s\">%s</a></pre></td><td>%s</td></tr>\n",
						gravatarUrl, commitUrl, shortId, shortMessage);
				sb.append(row);
			}

			// compare link
			if (commits.size() > 1) {
				String compareUrl = getUrl(ticket.repository, base, tip);
				String compareText;
				if (commits.size() > maxCommits) {
					int diff = commits.size() - maxCommits;
					if (diff == 1) {
						compareText = "1 more commit";
					} else {
						compareText = String.format("%d more commits", diff);
					}
				} else {
					compareText = String.format("view comparison of these %s commits", commits.size());
				}
				sb.append(String.format("<%s|%s>", compareUrl, compareText));
			}
			sb.append("</tbody></table>");

			msg = sb.toString();

		} else if (change.isMerge()) {
			/*
			 * Merged
			 */
			msg = String.format("%s has merged %s %s to <b>%s</b>", author, repo, url, ticket.mergeTo);
		} else if (change.isStatusChange()) {
			/*
			 * Status Change
			 */
			msg = String.format("%s has changed the status of %s %s", author, repo, url);
		} else if (change.hasComment() && settings.getBoolean(Plugin.SETTING_POST_TICKET_COMMENTS, true)) {
			/*
			 * Comment
			 */
			msg = String.format("%s has commented on %s %s", author, repo, url);
		}

		if (msg == null) {
			// not a change we are reporting
			return;
		}

		Color color = Color.gray;
    	if (change.isStatusChange()) {
    		// status change
    		switch (ticket.status) {
    		case Abandoned:
    		case Declined:
    		case Invalid:
    		case Wontfix:
    		case Duplicate:
    			color= Color.red;
    			break;
    		case On_Hold:
    			color = Color.yellow;
    			break;
    		case Closed:
    		case Fixed:
    		case Merged:
    		case Resolved:
    			color = Color.green;
    			break;
    		default:
    			break;
    		}
    	} else if (change.hasComment()) {
    		color = Color.yellow;
    	}

		StringBuilder sb = new StringBuilder();
		sb.append(msg);
		fields(sb, ticket, change, fieldExclusions);

    	Payload payload = Payload.html(sb.toString());
		payload.setColor(color);

		IRepositoryManager repositoryManager = GitblitContext.getManager(IRepositoryManager.class);
		RepositoryModel repository = repositoryManager.getRepositoryModel(ticket.repository);
   		hipChatter.setRoom(repository, payload);
   		hipChatter.sendAsync(payload);
    }

    protected void fields(StringBuilder sb, TicketModel ticket, Change change, Set<TicketModel.Field> fieldExclusions) {
    	Map<TicketModel.Field, String> filtered = new HashMap<TicketModel.Field, String>();
    	if (change.hasFieldChanges()) {
    		for (Map.Entry<TicketModel.Field, String> fc : change.fields.entrySet()) {
    			if (!fieldExclusions.contains(fc.getKey())) {
    				// field is included
    				filtered.put(fc.getKey(), fc.getValue());
    			}
    		}
    	}

    	if (change.hasComment() && settings.getBoolean(Plugin.SETTING_POST_TICKET_COMMENTS, true)) {
    		// transform Markdown comment
    		sb.append("<br/>");
    		String comment = renderMarkdown(change.comment.text, ticket.repository);
    		sb.append(comment);
    	}

    	// ensure we have some basic context fields
    	if (!filtered.containsKey(TicketModel.Field.title)) {
    		filtered.put(TicketModel.Field.title, ticket.title);
    	}
    	if (!filtered.containsKey(TicketModel.Field.responsible)) {
    		if (!StringUtils.isEmpty(ticket.responsible)) {
    			filtered.put(TicketModel.Field.responsible, ticket.responsible);
    		}
    	}
    	if (!filtered.containsKey(TicketModel.Field.milestone)) {
    		if (!StringUtils.isEmpty(ticket.milestone)) {
    			filtered.put(TicketModel.Field.milestone, ticket.milestone);
    		}
    	}

    	// sort by field ordinal
    	List<TicketModel.Field> fields = new ArrayList<TicketModel.Field>(filtered.keySet());
    	Collections.sort(fields);

    	if (fields.size() > 0) {
			sb.append("\n<table><tbody>\n");
			for (TicketModel.Field field : fields) {
				String value;
				if (filtered.get(field) == null) {
					continue;
				} else {
					value = filtered.get(field);

					if (TicketModel.Field.body == field) {
						// transform the body to html
						value = renderMarkdown(value, ticket.repository);
					} else if (TicketModel.Field.topic == field) {
						// link bugtraq matches
						value = renderBugtraq(value, ticket.repository);
					}
				}
				sb.append(String.format("<tr><td><b>%1$s:<b/></td><td>%2$s</td></tr>\n", field.name(), value));
			}
			sb.append("</tbody></table>\n");
    	}
    }

    protected String renderMarkdown(String markdown, String repository) {
    	if (StringUtils.isEmpty(markdown)) {
    		return markdown;
    	}

		// transform the body to html
    	String bugtraq = renderBugtraq(markdown, repository);
		String html = MarkdownUtils.transformGFM(settings, bugtraq, repository);

		// strip paragraph tags
		html = html.replace("<p>", "");
		html = html.replace("</p>", "<br/><br/>");
		return html;
    }

    protected String renderBugtraq(String value, String repository) {
    	if (StringUtils.isEmpty(value)) {
    		return value;
    	}

    	IRepositoryManager repositoryManager = GitblitContext.getManager(IRepositoryManager.class);
		Repository db = repositoryManager.getRepository(repository);
		try {
			BugtraqProcessor bugtraq = new BugtraqProcessor(settings);
			value = bugtraq.processPlainCommitMessage(db, repository, value);
		} finally {
			db.close();
		}
		return value;
    }

    /**
     * Determine if a ticket should be posted to a HipChat room.
     *
     * @param ticket
     * @return true if the ticket should be posted to a HipChat room
     */
    protected boolean shallPost(TicketModel ticket) {
    	IRuntimeManager runtimeManager = GitblitContext.getManager(IRuntimeManager.class);
    	boolean shallPostTicket = runtimeManager.getSettings().getBoolean(Plugin.SETTING_POST_TICKETS, true);
    	if (!shallPostTicket) {
    		return false;
    	}

		IRepositoryManager repositoryManager = GitblitContext.getManager(IRepositoryManager.class);
		RepositoryModel repository = repositoryManager.getRepositoryModel(ticket.repository);
		boolean shallPostRepo = hipChatter.shallPost(repository);
		return shallPostRepo;
    }

    protected String getUrl(TicketModel ticket) {
    	return GitblitContext.getManager(IGitblit.class).getTicketService().getTicketUrl(ticket);
    }

    /**
     * Returns a link appropriate for the push.
     *
     * If both new and old ids are null, the summary page link is returned.
     *
     * @param repo
     * @param oldId
     * @param newId
     * @return a link
     */
    protected String getUrl(String repo, String oldId, String newId) {
    	IRuntimeManager runtimeManager = GitblitContext.getManager(IRuntimeManager.class);
		String canonicalUrl = runtimeManager.getSettings().getString(Keys.web.canonicalUrl, "https://localhost:8443");

		if (oldId == null && newId != null) {
			// create
			final String hrefPattern = "{0}/commit?r={1}&h={2}";
			return MessageFormat.format(hrefPattern, canonicalUrl, repo, newId);
		} else if (oldId != null && newId == null) {
			// log
			final String hrefPattern = "{0}/log?r={1}&h={2}";
			return MessageFormat.format(hrefPattern, canonicalUrl, repo, oldId);
		} else if (oldId != null && newId != null) {
			// update/compare
			final String hrefPattern = "{0}/compare?r={1}&h={2}..{3}";
			return MessageFormat.format(hrefPattern, canonicalUrl, repo, oldId, newId);
		} else if (oldId == null && newId == null) {
			// summary page
			final String hrefPattern = "{0}/summary?r={1}";
			return MessageFormat.format(hrefPattern, canonicalUrl, repo);
		}

		return null;
    }

    private List<RevCommit> getCommits(String repositoryName, String baseId, String tipId) {
    	IRepositoryManager repositoryManager = GitblitContext.getManager(IRepositoryManager.class);
    	Repository db = repositoryManager.getRepository(repositoryName);
    	List<RevCommit> list = new ArrayList<RevCommit>();
		RevWalk walk = new RevWalk(db);
		walk.reset();
		walk.sort(RevSort.TOPO);
		walk.sort(RevSort.REVERSE, true);
		try {
			RevCommit tip = walk.parseCommit(db.resolve(tipId));
			RevCommit base = walk.parseCommit(db.resolve(baseId));
			walk.markStart(tip);
			walk.markUninteresting(base);
			for (;;) {
				RevCommit c = walk.next();
				if (c == null) {
					break;
				}
				list.add(c);
			}
		} catch (IOException e) {
			// Should never happen, the core receive process would have
			// identified the missing object earlier before we got control.
			log.error("failed to get commits", e);
		} finally {
			walk.release();
			db.close();
		}
		return list;
	}
}