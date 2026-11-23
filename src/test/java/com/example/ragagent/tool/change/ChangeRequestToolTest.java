package com.example.ragagent.tool.change;

import com.example.ragagent.audit.AuditLogger;
import com.example.ragagent.tool.ToolCaller;
import com.example.ragagent.tool.ToolGuard;
import com.example.ragagent.workflow.EditIntent;
import com.example.ragagent.workflow.KnowledgeEditWorkflow;
import com.example.ragagent.workflow.MaintenanceWorkflow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code requestDocumentChange} — 내용 삭제·설명 수정은 받지 않고(6b 로), 사용 중단·주의 사항·지시문만 요청은 자연어 워크플로로 넘긴다. */
class ChangeRequestToolTest {

    private final MaintenanceWorkflow maintenance = mock(MaintenanceWorkflow.class);
    private final KnowledgeEditWorkflow edit = mock(KnowledgeEditWorkflow.class);
    private final ChangeRequestTool tool = new ChangeRequestTool(maintenance, new ToolGuard(mock(AuditLogger.class)), edit);
    private final org.springframework.ai.chat.model.ToolContext admin = new ToolCaller("root", true, "mcp").toContext();

    @Test
    @DisplayName("type=remove·revise 는 시작하지 않고 6b 를 쓰라는 사유로 거절한다")
    void removeAndReviseAreRefused() {
        for (String type : new String[] {"remove", "revise", " REMOVE "}) {
            assertThatThrownBy(() -> tool.requestDocumentChange("B 를 지워", null, null, null, null, type, "B", "새 설명", null, admin))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("proposeChunkEdit");
        }
        verify(edit, never()).start(any(), any(), any(), any());
        verify(maintenance, never()).start(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    @DisplayName("type=deprecate 는 그 의도로, 지시문만이면 해석을 워크플로에 맡긴다(폼 전용 유형이면 거기서 실패한다)")
    void deprecateAndInstructionOnlyStart() {
        when(edit.start(anyString(), anyString(), any(), any())).thenReturn(new KnowledgeEditWorkflow.Started(7, "running"));

        assertThat(tool.requestDocumentChange("A 사용 중단", null, null, null, null, "deprecate", "A", null, null, admin).jobId()).isEqualTo(7);
        ArgumentCaptor<EditIntent> intent = ArgumentCaptor.forClass(EditIntent.class);
        verify(edit).start(eq("root"), eq("A 사용 중단"), intent.capture(), eq(Locale.KOREAN));
        assertThat(intent.getValue().type()).isEqualTo("deprecate");

        tool.requestDocumentChange("B 설명을 지워", null, null, null, null, null, null, null, null, admin);
        verify(edit).start(eq("root"), eq("B 설명을 지워"), eq(null), eq(Locale.KOREAN));
    }
}
