package com.shellmind.domain.coding.model;

import com.shellmind.domain.coding.model.entity.CodePatchEntity;
import com.shellmind.domain.coding.model.valobj.CodePatchStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodePatchEntityTest {

    private static CodePatchEntity newPatch() {
        String diff = "--- a/x\n+++ b/x\n-old\n+new1\n+new2\n";
        return CodePatchEntity.applied("run", "session", "user", "/ws/x", "/ws/.x.bak", diff);
    }

    @Test
    void appliedPatchCountsDiffLines() {
        CodePatchEntity patch = newPatch();
        assertNotNull(patch.getPatchId());
        assertEquals(CodePatchStatus.APPLIED, patch.getStatus());
        assertEquals(2, patch.getLinesAdded());
        assertEquals(1, patch.getLinesRemoved());
        assertTrue(patch.isRevertible());
    }

    @Test
    void approveThenRejectIsAllowed() {
        CodePatchEntity patch = newPatch();
        patch.approve();
        assertEquals(CodePatchStatus.APPROVED, patch.getStatus());
        assertTrue(patch.isRevertible());
        patch.reject();
        assertEquals(CodePatchStatus.REJECTED, patch.getStatus());
        assertFalse(patch.isRevertible());
    }

    @Test
    void cannotApproveTwiceOrAfterRevert() {
        CodePatchEntity approved = newPatch();
        approved.approve();
        assertThrows(IllegalStateException.class, approved::approve);

        CodePatchEntity rolledBack = newPatch();
        rolledBack.markRolledBack();
        assertEquals(CodePatchStatus.ROLLED_BACK, rolledBack.getStatus());
        assertThrows(IllegalStateException.class, rolledBack::approve, "A rolled-back patch cannot be approved");
        assertThrows(IllegalStateException.class, rolledBack::reject);
        assertThrows(IllegalStateException.class, rolledBack::markRolledBack);
    }

    @Test
    void rejectedIsTerminal() {
        CodePatchEntity patch = newPatch();
        patch.reject();
        assertThrows(IllegalStateException.class, patch::approve);
        assertThrows(IllegalStateException.class, patch::markRolledBack);
    }
}
