package com.repocortex;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// start Postgres via compose.yaml for this test too (Boot skips Docker Compose in tests by default)
@SpringBootTest(properties = "spring.docker.compose.skip.in-tests=false")
class RepoCortexApplicationTests {

    @Test
    void contextLoads() {
    }

}
