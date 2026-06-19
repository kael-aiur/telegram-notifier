package site.kael.telegram.notifier;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class NotifierApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotifierApplication.class, args);
    }
}
