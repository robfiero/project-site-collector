package com.signalsentinel.service.email;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.Properties;

public final class SmtpEmailSender implements EmailSender {
    private final Session session;
    private final String from;

    public SmtpEmailSender(String host, int port, String username, String password, String from) {
        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        this.session = Session.getInstance(props, new jakarta.mail.Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
            }
        });
        this.from = from;
    }

    @Override
    public void send(EmailMessage message) {
        try {
            MimeMessage mimeMessage = new MimeMessage(session);
            mimeMessage.setFrom(new InternetAddress(from));
            mimeMessage.setRecipients(Message.RecipientType.TO, InternetAddress.parse(message.to()));
            mimeMessage.setSubject(message.subject());
            mimeMessage.setText(message.body());
            Transport.send(mimeMessage);
        } catch (MessagingException e) {
            throw new IllegalStateException("Unable to send SMTP email", e);
        }
    }
}
