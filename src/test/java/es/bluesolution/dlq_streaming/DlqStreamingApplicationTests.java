package es.bluesolution.dlq_streaming;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"spring.autoconfigure.exclude="
				+ "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
				+ "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
				+ "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
				+ "org.springframework.boot.batch.autoconfigure.BatchAutoConfiguration,"
				+ "org.springframework.boot.batch.jdbc.autoconfigure.BatchJdbcAutoConfiguration"
})
class DlqStreamingApplicationTests {

	@Test
	void contextLoads() {
	}

}
