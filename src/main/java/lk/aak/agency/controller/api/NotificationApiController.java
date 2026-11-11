package lk.aak.agency.controller.api;

import lk.aak.agency.service.NotificationService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The bell: what needs the signed-in person right now, each with the page where it is dealt with. */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationApiController {

    private final NotificationService notificationService;

    public NotificationApiController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public List<NotificationService.Notification> list(Authentication authentication) {
        return notificationService.forUser(authentication);
    }
}
