package com.example.crediSense.Service.impl;

import com.example.crediSense.repository.AuditEventRepository;
import com.example.crediSense.repository.DecisionFinaleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Le câblage Spring de l'envoi automatique : les services se construisent ensemble, et
 * l'interrupteur AUTO_ENVOI_EMAIL est bien lu. (DemoApplicationTests, qui charge toute
 * l'application, a besoin d'une vraie base PostgreSQL : il ne peut pas servir à ça.)
 */
class NotificationWiringTest {

    private AnnotationConfigApplicationContext contexte(Map<String, Object> proprietes) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", proprietes));
        ctx.registerBean(JavaMailSender.class, () -> mock(JavaMailSender.class));
        ctx.registerBean(DecisionFinaleRepository.class, () -> mock(DecisionFinaleRepository.class));
        ctx.registerBean(AuditEventRepository.class, () -> mock(AuditEventRepository.class));
        ctx.register(RapportPdfService.class, ResultatEmailService.class, AuditService.class,
                NotificationDecisionService.class);
        ctx.refresh();
        return ctx;
    }

    @Test
    void les_trois_services_se_construisent_ensemble() {
        try (AnnotationConfigApplicationContext ctx = contexte(Map.of("spring.mail.username", "banque@example.com"))) {
            assertNotNull(ctx.getBean(RapportPdfService.class));
            assertNotNull(ctx.getBean(ResultatEmailService.class));
            assertNotNull(ctx.getBean(NotificationDecisionService.class));
        }
    }

    @Test
    void l_expediteur_vient_de_la_configuration_de_la_messagerie() {
        try (AnnotationConfigApplicationContext ctx = contexte(Map.of("spring.mail.username", "banque@example.com"))) {
            assertEquals("banque@example.com",
                    ReflectionTestUtils.getField(ctx.getBean(ResultatEmailService.class), "fromEmail"));
        }
    }

    @Test
    void l_envoi_automatique_est_actif_par_defaut() {
        try (AnnotationConfigApplicationContext ctx = contexte(Map.of("spring.mail.username", "x@example.com"))) {
            assertEquals(true, ReflectionTestUtils.getField(ctx.getBean(NotificationDecisionService.class), "autoEnvoi"));
        }
    }

    @Test
    void l_envoi_automatique_se_coupe_par_la_configuration() {
        Map<String, Object> proprietes = Map.of("spring.mail.username", "x@example.com",
                                                "app.notifications.auto-envoi", "false");
        try (AnnotationConfigApplicationContext ctx = contexte(proprietes)) {
            assertEquals(false, ReflectionTestUtils.getField(ctx.getBean(NotificationDecisionService.class), "autoEnvoi"));
        }
    }

    @Test
    void la_variable_d_environnement_est_branchee_sur_la_propriete() throws Exception {
        String fichier = new String(new ClassPathResource("application.properties").getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        assertTrue(fichier.contains("app.notifications.auto-envoi=${AUTO_ENVOI_EMAIL:true}"),
                "AUTO_ENVOI_EMAIL doit piloter app.notifications.auto-envoi, actif par défaut");
    }
}
