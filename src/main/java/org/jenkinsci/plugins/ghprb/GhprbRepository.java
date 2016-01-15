package org.jenkinsci.plugins.ghprb;

import com.google.common.annotations.VisibleForTesting;

import com.google.gson.Gson;
import hudson.model.AbstractBuild;
import hudson.model.TaskListener;
import jenkins.model.Jenkins;

import org.apache.http.NoHttpResponseException;
import org.apache.http.client.fluent.Request;
import org.apache.http.client.fluent.Response;
import org.apache.http.entity.ContentType;
import org.bouncycastle.cert.ocsp.Req;
import org.kohsuke.github.*;
import org.kohsuke.github.GHEventPayload.IssueComment;
import org.kohsuke.github.GHEventPayload.PullRequest;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Honza Brázdil <jbrazdil@redhat.com>
 */
public class GhprbRepository {

    private static final Logger logger = Logger.getLogger(GhprbRepository.class.getName());
    private static final EnumSet<GHEvent> HOOK_EVENTS = EnumSet.of(GHEvent.ISSUE_COMMENT, GHEvent.PULL_REQUEST);

    private final String reponame;
    private final ConcurrentMap<Integer, GhprbPullRequest> pulls;

    private GHRepository ghRepository;
    private Ghprb helper;

    public GhprbRepository(String user, String repository, Ghprb helper, ConcurrentMap<Integer, GhprbPullRequest> pulls) {
        this.reponame = user + "/" + repository;
        this.helper = helper;
        this.pulls = pulls;
    }

    public void init() {
        for (GhprbPullRequest pull : pulls.values()) {
            pull.init(helper, this);
        }
        // make the initial check call to populate our data structures
        initGhRepository();
    }

    private boolean initGhRepository() {
        GitHub gitHub = null;
        try {
            gitHub = helper.getGitHub().get();
            if (gitHub.getRateLimit().remaining == 0) {
                return false;
            }
        } catch (FileNotFoundException ex) {
            logger.log(Level.INFO, "Rate limit API not found.");
        } catch (IOException ex) {
            logger.log(Level.SEVERE, "Error while accessing rate limit API", ex);
            return false;
        }

        if (ghRepository == null) {
            try {
                ghRepository = gitHub.getRepository(reponame);
            } catch (IOException ex) {
                logger.log(Level.SEVERE, "Could not retrieve GitHub repository named " + reponame + " (Do you have properly set 'GitHub project' field in job configuration?)", ex);
                return false;
            }
        }
        return true;
    }

    public void check() {
        if (!initGhRepository()) {
            return;
        }

        List<GHPullRequest> openPulls;
        try {
            openPulls = ghRepository.getPullRequests(GHIssueState.OPEN);
        } catch (IOException ex) {
            logger.log(Level.SEVERE, "Could not retrieve open pull requests.", ex);
            return;
        }
        Set<Integer> closedPulls = new HashSet<Integer>(pulls.keySet());

        for (GHPullRequest pr : openPulls) {
            if (pr.getHead() == null) {
                try {
                    pr = ghRepository.getPullRequest(pr.getNumber());
                } catch (IOException ex) {
                    logger.log(Level.SEVERE, "Could not retrieve pr " + pr.getNumber(), ex);
                    return;
                }
            }
            check(pr);
            closedPulls.remove(pr.getNumber());
        }

        // remove closed pulls so we don't check them again
        for (Integer id : closedPulls) {
            pulls.remove(id);
        }
    }

    private void check(GHPullRequest pr) {
        final Integer id = pr.getNumber();
        GhprbPullRequest pull;
        if (pulls.containsKey(id)) {
            pull = pulls.get(id);
        } else {
            pulls.putIfAbsent(id, new GhprbPullRequest(pr, helper, this));
            pull = pulls.get(id);
        }
        pull.check(pr);
    }

    public void createCommitStatus(AbstractBuild<?, ?> build, GHCommitState state, String message, int id, String context, PrintStream stream) {
        String sha1 = build.getCause(GhprbCause.class).getCommit();
        createCommitStatus(build, sha1, state, Jenkins.getInstance().getRootUrl() + build.getUrl(), message, id, context, stream);
    }

    public void createCommitStatus(String sha1, GHCommitState state, String url, String message, int id, String context) {
    	createCommitStatus(null, sha1, state, url, message, id, context, null);
    }
    
    public void createCommitStatus(AbstractBuild<?, ?> build, String sha1, GHCommitState state, String url, String message, int id, String context, PrintStream stream) {
        String newMessage = String.format("Setting status of %s to %s with url %s and message: %s", sha1, state, url, message);
        if (stream != null) {
            stream.println(newMessage);
        } else {
            logger.log(Level.INFO, newMessage);
        }
        try {
            postCommitStatus(sha1, state, url, message, context);
        } catch (FileNotFoundException ex) {
            newMessage = "FileNotFoundException means that the credentials Jenkins is using is probably wrong. Or that something is really wrong with github.";
            if (stream != null) {
                stream.println(newMessage);
                ex.printStackTrace(stream);
            } else {
                logger.log(Level.INFO, newMessage, ex);
            }
        } catch (IOException ex) {
            newMessage = "Could not update commit status of the Pull Request on GitHub.";
            if (stream != null) {
                stream.println(newMessage);
                ex.printStackTrace(stream);
            } else {
                logger.log(Level.INFO, newMessage, ex);
            }
            if (GhprbTrigger.getDscp().getUseComments()) {
                
                if (state == GHCommitState.SUCCESS) {
                    message = message + " " + GhprbTrigger.getDscp().getMsgSuccess(build);
                } else if (state == GHCommitState.FAILURE) {
                    message = message + " " + GhprbTrigger.getDscp().getMsgFailure(build);
                }
                if (GhprbTrigger.getDscp().getUseDetailedComments() || (state == GHCommitState.SUCCESS || state == GHCommitState.FAILURE)) {
                  logger.log(Level.INFO, "Trying to send comment.", ex);
                  addComment(id, message);
                }
            } else {
                logger.log(Level.SEVERE, "Could not update commit status of the Pull Request on GitHub.");
            }
        }
    }

    private void postCommitStatus(String sha1, GHCommitState state, String url, String message, String context) throws IOException {
        Map<String, String> map = new HashMap<String, String>();
        map.put("state", state.name().toLowerCase(Locale.ENGLISH));
        map.put("target_url", url);
        map.put("description", message);

        if (context != null && !context.isEmpty()) {
            map.put("context", context);
        }

        Gson gson = new Gson();
        String body = gson.toJson(map);

        String baseUrl = "https://api.github.com/repos/%s/statuses/%s";

        try {
            Response response = Request.Post(String.format(baseUrl, this.reponame, sha1))
                    .addHeader("Authorization", "token " + GhprbTrigger.getDscp().getStatusAccessToken())
                    .addHeader("Connection", "close")
                    .bodyString(body, ContentType.APPLICATION_JSON)
                    .execute();
            logger.log(Level.INFO, response.returnContent().asString());
        } catch (NoHttpResponseException e) {
            logger.log(Level.INFO, "Retrying...");
            Response response = Request.Post(String.format(baseUrl, this.reponame, sha1))
                    .addHeader("Authorization", "token " + GhprbTrigger.getDscp().getStatusAccessToken())
                    .bodyString(body, ContentType.APPLICATION_JSON)
                    .execute();
            logger.log(Level.INFO, response.returnContent().asString());
        }
    }

    public String getName() {
        return reponame;
    }

    public void addComment(int id, String comment) {
      addComment(id, comment, null, null);
    }

    public void addComment(int id, String comment, AbstractBuild<?, ?> build, TaskListener listener) {
        if (comment.trim().isEmpty())
            return;

        if (build != null && listener != null) {
          try {
            comment = build.getEnvironment(listener).expand(comment);
          } catch (Exception e) {
            logger.log(Level.SEVERE, "Error", e);
          }
        }

        try {
            ghRepository.getPullRequest(id).comment(comment);
        } catch (IOException ex) {
            logger.log(Level.SEVERE, "Couldn't add comment to pull request #" + id + ": '" + comment + "'", ex);
        }
    }

    public void closePullRequest(int id) {
        try {
            ghRepository.getPullRequest(id).close();
        } catch (IOException ex) {
            logger.log(Level.SEVERE, "Couldn't close the pull request #" + id + ": '", ex);
        }
    }

    private boolean hookExist() throws IOException {
        for (GHHook h : ghRepository.getHooks()) {
            if (!"web".equals(h.getName())) {
                continue;
            }
            if (!getHookUrl().equals(h.getConfig().get("url"))) {
                continue;
            }
            return true;
        }
        return false;
    }

    public boolean createHook() {
        if (ghRepository == null) {
            logger.log(Level.INFO, "Repository not available, cannot set pull request hook for repository {0}", reponame);
            return false;
        }
        try {
            if (hookExist()) {
                return true;
            }
            Map<String, String> config = new HashMap<String, String>();
            config.put("url", new URL(getHookUrl()).toExternalForm());
            config.put("insecure_ssl", "1");
            ghRepository.createHook("web", config, HOOK_EVENTS, true);
            return true;
        } catch (IOException ex) {
            logger.log(Level.SEVERE, "Couldn''t create web hook for repository {0}. Does the user (from global configuration) have admin rights to the repository?", reponame);
            return false;
        }
    }

    private static String getHookUrl() {
        return Jenkins.getInstance().getRootUrl() + GhprbRootAction.URL + "/";
    }

    public GHPullRequest getPullRequest(int id) throws IOException {
        return ghRepository.getPullRequest(id);
    }

    void onIssueCommentHook(IssueComment issueComment) throws IOException {
        int id = issueComment.getIssue().getNumber();
        logger.log(Level.FINER, "Comment on issue #{0} from {1}: {2}", new Object[]{id, issueComment.getComment().getUser(), issueComment.getComment().getBody()});
        if (!"created".equals(issueComment.getAction())) {
            return;
        }
        GhprbPullRequest pull = pulls.get(id);
        if (pull == null) {
            pull = new GhprbPullRequest(ghRepository.getPullRequest(id), helper, this);
            pulls.put(id, pull);
        }
        pull.check(issueComment.getComment());
        GhprbTrigger.getDscp().save();
    }

    void onPullRequestHook(PullRequest pr) {
        if ("opened".equals(pr.getAction()) || "reopened".equals(pr.getAction())) {
            GhprbPullRequest pull = pulls.get(pr.getNumber());
            if (pull == null) {
                pulls.putIfAbsent(pr.getNumber(), new GhprbPullRequest(pr.getPullRequest(), helper, this));
                pull = pulls.get(pr.getNumber());
            }
            pull.check(pr.getPullRequest());
        } else if ("synchronize".equals(pr.getAction())) {
            GhprbPullRequest pull = pulls.get(pr.getNumber());
            if (pull == null) {
                pulls.putIfAbsent(pr.getNumber(), new GhprbPullRequest(pr.getPullRequest(), helper, this));
                pull = pulls.get(pr.getNumber());
            }
            if (pull == null) {
                logger.log(Level.SEVERE, "Pull Request #{0} doesn''t exist", pr.getNumber());
                return;
            }
            pull.check(pr.getPullRequest());
        } else if ("closed".equals(pr.getAction())) {
            pulls.remove(pr.getNumber());
        } else {
            logger.log(Level.WARNING, "Unknown Pull Request hook action: {0}", pr.getAction());
        }
        GhprbTrigger.getDscp().save();
    }

    @VisibleForTesting
    void setHelper(Ghprb helper) {
        this.helper = helper;
    }
}