package com.traceability.convocatoria.domain.model;

import com.traceability.convocatoria.domain.exception.AssignmentAlreadyRemovedException;
import com.traceability.convocatoria.domain.exception.EmployeeSelfAssignmentNotAllowedException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** implementation_plan.md §3.2; Enmienda §4.1–§4.3. */
class CampaignAssignmentTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

    @Test
    void assignEmployeeCreatesActiveEmployeeAssignment() {
        CampaignAssignment a = CampaignAssignment.assignEmployee("a-1", "camp", "emp", "admin", NOW);
        assertEquals(ActingRole.EMPLOYEE, a.getActingRole());
        assertEquals(AssignmentStatus.ACTIVE, a.getStatus());
        assertEquals("emp", a.getEmployeeRef());
        assertEquals("admin", a.getAssignedBy());
        assertFalse(a.isSelfAssigned());
        assertNull(a.getRemovedAt());
    }

    @Test
    void employeeSelfAssignmentIsRejected() {
        assertThrows(EmployeeSelfAssignmentNotAllowedException.class,
                () -> CampaignAssignment.assignEmployee("a-1", "camp", "same", "same", NOW));
    }

    @Test
    void administratorDesignationByAnotherAdministratorIsNotSelfAssigned() {
        CampaignAssignment a = CampaignAssignment.designateAdministrator("a-1", "camp", "admin-2", "admin-1", NOW);
        assertEquals(ActingRole.ADMINISTRATOR, a.getActingRole());
        assertFalse(a.isSelfAssigned());
    }

    @Test
    void administratorSelfDesignationIsAllowedAndMarked() {
        CampaignAssignment a = CampaignAssignment.designateAdministrator("a-1", "camp", "admin", "admin", NOW);
        assertEquals(AssignmentStatus.ACTIVE, a.getStatus());
        assertTrue(a.isSelfAssigned());
    }

    @Test
    void removeIsTerminal() {
        CampaignAssignment a = CampaignAssignment.assignEmployee("a-1", "camp", "emp", "admin", NOW);
        a.remove(NOW.plusSeconds(10));
        assertEquals(AssignmentStatus.REMOVED, a.getStatus());
        assertEquals(NOW.plusSeconds(10), a.getRemovedAt());
        assertThrows(AssignmentAlreadyRemovedException.class, () -> a.remove(NOW.plusSeconds(20)));
        assertEquals(NOW.plusSeconds(10), a.getRemovedAt());
    }
}
