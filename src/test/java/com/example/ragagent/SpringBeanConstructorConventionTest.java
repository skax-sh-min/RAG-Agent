package com.example.ragagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스프링 빈의 생성자 규약 — 생성자가 둘 이상이면 주입에 쓸 하나에 {@code @Autowired} 를 단다.
 *
 * <p>스프링은 생성자가 하나면 그것을 쓰고, 여럿이면 {@code @Autowired} 가 붙은 것을, 그것도 없으면 인자 없는 생성자를
 * 찾는다 — 그마저 없으면 <b>앱이 뜨지 않는다</b>("No default constructor found"). 이 저장소는 테스트용·하위호환
 * 생성자를 더하는 일이 잦은데(양보 시간을 줄이는 생성자, 협력자 하나를 뺀 생성자), 그 순간 이 규칙이 깨지고 단위
 * 테스트는 생성자를 직접 부르므로 <b>전부 통과한다</b>. 전체 컨텍스트를 띄우는 테스트는 vec0 확장이 있어야 돌아
 * 평소에는 건너뛰므로, 실제로 {@code ClarifiedQuestionBackfill} 이 그렇게 깨진 채 빌드를 통과했다(서버를 띄워
 * 보고서야 알았다). 그래서 컴포넌트 스캔 대상 전부를 여기서 본다.
 */
class SpringBeanConstructorConventionTest {

    @Test
    @DisplayName("생성자가 둘 이상인 스프링 빈은 주입할 생성자 하나에 @Autowired 가 있다 — 없으면 앱이 뜨지 않는다")
    void beansWithSeveralConstructorsMarkTheOneToInject() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(true);
        List<String> violations = new ArrayList<>();
        int scanned = 0;
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.example.ragagent")) {
            scanned++;
            Class<?> type = Class.forName(candidate.getBeanClassName());
            Constructor<?>[] constructors = type.getDeclaredConstructors();
            if (constructors.length < 2) continue;
            long autowired = Arrays.stream(constructors).filter(c -> c.isAnnotationPresent(Autowired.class)).count();
            boolean noArg = Arrays.stream(constructors).anyMatch(c -> c.getParameterCount() == 0);
            if (autowired != 1 && !noArg) {
                violations.add(type.getName() + " — 생성자 " + constructors.length + "개, @Autowired " + autowired + "개");
            }
        }

        assertThat(scanned).as("컴포넌트 스캔이 아무것도 찾지 못했다면 패키지 이름을 고칠 것").isGreaterThan(50);
        assertThat(violations)
                .as("주입에 쓸 생성자 하나에 @Autowired 를 달 것 — 없으면 스프링이 고르지 못해 기동이 실패한다")
                .isEmpty();
    }
}
