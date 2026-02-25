package com.signalsentinel.service.email;

import java.time.Instant;
import java.util.List;

public record EmailMessage(
        String to,
        String subject,
        String body,
        List<String> links,
        Instant createdAt
) {
}
