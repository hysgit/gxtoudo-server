package com.gxtodo.server;

import org.springframework.core.env.Environment;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Properties;

@Service
final class ResetMail {
    private final Environment environment;

    ResetMail(Environment environment) {
        this.environment = environment;
    }

    void validate() {
        String origin = environment.getProperty("GXTODO_PUBLIC_ORIGIN", "");
        try {
            URI uri = URI.create(origin);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) {
            throw new ApiException(1, "实例管理员尚未配置 HTTPS 邮件找回地址");
        }
        if (environment.getProperty("GXTODO_SMTP_HOST", "").isBlank()
                || environment.getProperty("GXTODO_SMTP_FROM", "").isBlank()) {
            throw new ApiException(1, "实例管理员尚未配置邮件找回密码");
        }
    }

    void send(Accounts.Reset reset) {
        if (reset == null) return;
        validate();
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(environment.getRequiredProperty("GXTODO_SMTP_HOST"));
        int port = environment.getProperty("GXTODO_SMTP_PORT", Integer.class, 465);
        sender.setPort(port);
        String username = environment.getProperty("GXTODO_SMTP_USER", "");
        sender.setUsername(username);
        sender.setPassword(environment.getProperty("GXTODO_SMTP_PASSWORD", ""));
        Properties options = sender.getJavaMailProperties();
        options.setProperty("mail.smtp.auth", Boolean.toString(!username.isBlank()));
        options.setProperty("mail.smtp.ssl.enable", Boolean.toString(port == 465));
        options.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        options.setProperty("mail.smtp.starttls.enable", Boolean.toString(port != 465));
        options.setProperty("mail.smtp.starttls.required", Boolean.toString(port != 465));
        options.setProperty("mail.smtp.connectiontimeout", "10000");
        options.setProperty("mail.smtp.timeout", "10000");
        options.setProperty("mail.smtp.writetimeout", "10000");
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(environment.getRequiredProperty("GXTODO_SMTP_FROM"));
        message.setTo(reset.email());
        message.setSubject("高效Todo 密码重置");
        String origin = environment.getRequiredProperty("GXTODO_PUBLIC_ORIGIN").replaceAll("/+$", "");
        message.setText("请在 20 分钟内打开以下链接重置密码。\n\n" + origin + "/reset?token=" + reset.token() + "\n");
        sender.send(message);
    }
}
