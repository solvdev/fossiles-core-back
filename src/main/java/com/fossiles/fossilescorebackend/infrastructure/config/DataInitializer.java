//package com.fossiles.fossilescorebackend.infrastructure.config;
//
//import com.fossiles.fossilescorebackend.application.port.UserRepositoryPort;
//import com.fossiles.fossilescorebackend.domain.model.User;
//import com.fossiles.fossilescorebackend.infrastructure.util.EncryptionUtil;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.boot.CommandLineRunner;
//import org.springframework.security.crypto.password.PasswordEncoder;
//import org.springframework.stereotype.Component;
//
///**
// * Inicializador de datos
// * Crea el usuario admin por defecto si no existe
// */
//@Component
//@RequiredArgsConstructor
//@Slf4j
//public class DataInitializer implements CommandLineRunner {
//
//    private final UserRepositoryPort userRepositoryPort;
//    private final PasswordEncoder passwordEncoder;
//    private final EncryptionUtil encryptionUtil;
//
//    @Override
//    public void run(String... args) throws Exception {
//        // Verificar si ya existe el usuario admin
//        if (userRepositoryPort.findByUsername("adminuser").isEmpty()) {
//            log.info("Creating default admin user...");
//
//            final String plainPassword = "test123";
//
//            // Hasheamos la contraseña con BCrypt para almacenar en BD (unidireccional)
//            String bcrypt = passwordEncoder.encode(plainPassword);
//
//            User adminUser = User.builder()
//                    .username("adminuser")
//                    .email("admin@fossiles.com")
//                    .password(bcrypt) // guardamos hash BCrypt
//                    .status("active")
//                    .build();
//
//            userRepositoryPort.save(adminUser);
//
//            // Generamos la versión AES (la que el frontend debe enviar) usando nuestro EncryptionUtil
//            // Este valor lo puedes pegar en Postman en el campo encryptedPassword
//            String aesForClient = encryptionUtil.encrypt(plainPassword);
//
//            log.info("Admin user created successfully: adminuser / {}", plainPassword);
//            log.info("Encrypted (AES) password to use from client (paste as encryptedPassword in Postman): {}", aesForClient);
//
//        } else {
//            log.info("Admin user already exists");
//        }
//    }
//}
