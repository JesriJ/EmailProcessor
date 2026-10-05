package com.jesri.email.ingest;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.UserCredentials;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Component
@ConditionalOnProperty(name = "ingest.use-mock-gmail", havingValue = "false")
public class LiveGmailClient implements GmailClient {

    private final IngestProperties properties;

    public LiveGmailClient(IngestProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<NormalizedEmail> fetchRecent(int maxMessages, int lookbackDays) {
        try {
            if (properties.googleClientId() == null || properties.googleClientId().isBlank()
                    || properties.googleClientSecret() == null || properties.googleClientSecret().isBlank()
                    || properties.googleRefreshToken() == null || properties.googleRefreshToken().isBlank()) {
                throw new IllegalStateException(
                        "Gmail OAuth credentials are not configured. Run: python scripts/gmail_oauth.py"
                );
            }
            UserCredentials credentials = UserCredentials.newBuilder()
                    .setClientId(properties.googleClientId())
                    .setClientSecret(properties.googleClientSecret())
                    .setRefreshToken(properties.googleRefreshToken())
                    .build();
            Gmail gmail = new Gmail.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials)
            ).setApplicationName("email-processor-ingest").build();

            long afterEpoch = Instant.now().minus(lookbackDays, ChronoUnit.DAYS).getEpochSecond();
            String extra = properties.query() == null ? "" : properties.query().trim();
            String query = ("after:" + afterEpoch + (extra.isEmpty() ? "" : " " + extra)).trim();

            List<Message> stubs = new ArrayList<>();
            String pageToken = null;
            while (stubs.size() < maxMessages) {
                long pageSize = Math.min(100L, maxMessages - stubs.size());
                ListMessagesResponse list = gmail.users().messages().list("me")
                        .setQ(query)
                        .setMaxResults(pageSize)
                        .setPageToken(pageToken)
                        .execute();
                if (list.getMessages() != null) {
                    stubs.addAll(list.getMessages());
                }
                pageToken = list.getNextPageToken();
                if (pageToken == null || pageToken.isBlank()) {
                    break;
                }
            }

            List<NormalizedEmail> result = new ArrayList<>();
            for (Message stub : stubs) {
                Message full = gmail.users().messages().get("me", stub.getId()).setFormat("full").execute();
                result.add(normalize(full));
            }
            return result;
        } catch (Exception ex) {
            throw new IllegalStateException("Gmail fetch failed: " + ex.getMessage(), ex);
        }
    }

    private NormalizedEmail normalize(Message message) {
        String subject = header(message, "Subject");
        String from = header(message, "From");
        String body = extractBody(message);
        Instant received = message.getInternalDate() == null
                ? Instant.now()
                : Instant.ofEpochMilli(message.getInternalDate());
        List<String> labels = message.getLabelIds() == null ? List.of() : message.getLabelIds();
        return new NormalizedEmail(
                "gmail-" + message.getId(),
                message.getId(),
                message.getThreadId(),
                subject,
                body,
                from,
                received,
                labels
        );
    }

    private String header(Message message, String name) {
        if (message.getPayload() == null || message.getPayload().getHeaders() == null) {
            return "";
        }
        for (MessagePartHeader header : message.getPayload().getHeaders()) {
            if (name.equalsIgnoreCase(header.getName())) {
                return header.getValue() == null ? "" : header.getValue();
            }
        }
        return "";
    }

    private String extractBody(Message message) {
        if (message.getPayload() == null) {
            return message.getSnippet() == null ? "" : message.getSnippet();
        }
        String plain = findBody(message.getPayload(), "text/plain");
        if (plain != null && !plain.isBlank()) {
            return plain;
        }
        String html = findBody(message.getPayload(), "text/html");
        if (html != null && !html.isBlank()) {
            return html.replaceAll("(?is)<script.*?</script>", " ")
                    .replaceAll("(?is)<style.*?</style>", " ")
                    .replaceAll("(?is)<[^>]+>", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
        }
        return message.getSnippet() == null ? "" : message.getSnippet();
    }

    private String findBody(MessagePart part, String mimeType) {
        if (part == null) {
            return null;
        }
        if (mimeType.equalsIgnoreCase(part.getMimeType())
                && part.getBody() != null
                && part.getBody().getData() != null) {
            return decode(part.getBody().getData());
        }
        if (part.getParts() != null) {
            for (MessagePart child : part.getParts()) {
                String found = findBody(child, mimeType);
                if (found != null) {
                    return found;
                }
            }
        }
        // Single-part messages sometimes only have body.data without mime match above.
        if (part.getBody() != null && part.getBody().getData() != null && part.getParts() == null) {
            return decode(part.getBody().getData());
        }
        return null;
    }

    private String decode(String data) {
        byte[] decoded = Base64.getUrlDecoder().decode(data);
        return new String(decoded, StandardCharsets.UTF_8);
    }
}
