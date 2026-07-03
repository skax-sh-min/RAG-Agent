package com.example.ragagent.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqliteUserDetailsServiceTest {

    @Test
    @DisplayName("findFirstAdmin: users 조회 실패 시 Optional.empty로 안전 폴백")
    void findFirstAdmin_returnsEmpty_whenUserQueryFails() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doNothing().when(jdbc).execute(anyString());
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenThrow(new DataAccessResourceFailureException("no such table: users"));

        SqliteUserDetailsService service = new SqliteUserDetailsService(jdbc);

        Optional<AppUserDetails> result = service.findFirstAdmin();

        assertThat(result).isEmpty();
    }
}
