package com.backend.boundedContext.member.out;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;

/**
 * 회원 컨텍스트의 메일 발송(Gmail SMTP)
 */
@Component
@RequiredArgsConstructor
public class MemberMailSender {

    private static final String SENDER_NAME = "팔레트";

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String senderAddress;

    // 이메일 인증코드 발송
    public void sendVerificationCode(String to, String code, long expireMinutes) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
            helper.setFrom(senderAddress, SENDER_NAME);
            helper.setTo(to);
            helper.setSubject("[팔레트] 이메일 인증코드 안내");
            helper.setText("""
                    팔레트 회원가입 인증코드입니다.

                    인증코드: %s

                    %d분 안에 입력해 주세요.
                    본인이 요청하지 않았다면 이 메일을 무시해 주세요.
                    """.formatted(code, expireMinutes));
            mailSender.send(message);
        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            throw new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR, e);
        }
    }
}
