package com.neuronest.backend.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class OtpService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int OTP_VALIDITY_MINUTES = 10;

    private final JavaMailSender mailSender;
    private final UserRepository userRepository;

    @Value("${spring.mail.username}")
    private String fromAddress;

    public OtpService(JavaMailSender mailSender, UserRepository userRepository) {
        this.mailSender = mailSender;
        this.userRepository = userRepository;
    }

    public void generateAndSendOtp(User user) {
        String otp = String.format("%06d", RANDOM.nextInt(1_000_000));
        user.setOtpCode(otp);
        user.setOtpExpiresAt(Instant.now().plus(OTP_VALIDITY_MINUTES, ChronoUnit.MINUTES));
        userRepository.save(user);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(user.getEmail());
        message.setSubject("Your NeuroNest verification code");
        message.setText("Your verification code is " + otp + ". It expires in " + OTP_VALIDITY_MINUTES + " minutes.");
        mailSender.send(message);
    }

    public boolean verifyOtp(User user, String submittedOtp) {
        if (user.getOtpCode() == null || user.getOtpExpiresAt() == null) {
            return false;
        }
        if (Instant.now().isAfter(user.getOtpExpiresAt())) {
            return false;
        }
        if (!user.getOtpCode().equals(submittedOtp)) {
            return false;
        }
        user.setEmailVerified(true);
        user.setOtpCode(null);
        user.setOtpExpiresAt(null);
        userRepository.save(user);
        return true;
    }
}