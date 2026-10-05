package com.jesri.email.admin;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class GmailLinks {

    private GmailLinks() {}

    /**
     * Opens the thread in Gmail so a human can click Reply.
     * Prefers thread id; falls back to message id.
     */
    static String openInGmail(String source, String threadId, String messageId) {
        if (source == null || !"gmail".equalsIgnoreCase(source)) {
            return null;
        }
        String id = (threadId != null && !threadId.isBlank()) ? threadId : messageId;
        if (id == null || id.isBlank() || id.startsWith("mock") || id.startsWith("thread-mock")) {
            // Mock ids are not real Gmail threads.
            return null;
        }
        return "https://mail.google.com/mail/u/0/#all/" + id;
    }

    static String mailtoReply(String sender, String subject) {
        if (sender == null || sender.isBlank()) {
            return null;
        }
        String email = sender;
        int lt = sender.indexOf('<');
        int gt = sender.indexOf('>');
        if (lt >= 0 && gt > lt) {
            email = sender.substring(lt + 1, gt).trim();
        }
        String reSubject = subject == null ? "" : subject;
        if (!reSubject.regionMatches(true, 0, "re:", 0, 3)) {
            reSubject = "Re: " + reSubject;
        }
        return "mailto:" + email
                + "?subject=" + URLEncoder.encode(reSubject, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
