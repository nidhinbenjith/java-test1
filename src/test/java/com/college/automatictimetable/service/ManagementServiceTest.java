package com.college.automatictimetable.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import com.college.automatictimetable.controller.TimetableController;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:timetable-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional
class ManagementServiceTest {
    @Autowired
    private ManagementService management;
    @Autowired
    private TimetableService timetable;
    @Autowired
    private TestRestTemplate http;
    @Autowired
    private TimetableController timetableController;

    @Test
    void createsUpdatesAndDeletesDepartmentRecords() {
        Map<String, Object> created = management.save("departments", null,
                Map.of("name", "Science", "code", "SCI"));
        Long id = (Long) created.get("id");
        assertEquals("Science", created.get("name"));

        Map<String, Object> updated = management.save("departments", id,
                Map.of("name", "Applied Science", "code", "AS"));
        assertEquals("Applied Science", updated.get("name"));

        management.delete("departments", id);
        assertTrue(management.list("departments").isEmpty());
    }

    @Test
    void rejectsMissingRequiredFieldsClearly() {
        var error = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> management.save("classrooms", null, Map.of("name", "Room A")));
        assertEquals(400, error.getStatusCode().value());
    }

    @Test
    void servesDashboardAndManagementApisWithoutCredentials() {
        assertEquals(200, http.getForEntity("/", String.class).getStatusCode().value());
        ResponseEntity<Map> dashboardDepartment = http.postForEntity("/api/departments",
                Map.of("name", "Dashboard department", "code", "DASH"), Map.class);
        assertEquals(201, dashboardDepartment.getStatusCode().value());
        ResponseEntity<String> dashboard = http.getForEntity("/api/dashboard", String.class);
        assertEquals(200, dashboard.getStatusCode().value());
        assertTrue(dashboard.getBody().contains("\"departments\":1"));
        assertTrue(dashboard.getBody().contains("\"conflicts\":0"));
        Long dashboardDepartmentId = ((Number) dashboardDepartment.getBody().get("id")).longValue();
        http.delete("/api/departments/{id}", dashboardDepartmentId);
        ResponseEntity<Map> created = http.postForEntity("/api/departments",
                Map.of("name", "Admin created", "code", "ADM"), Map.class);
        assertEquals(201, created.getStatusCode().value());
        Long departmentId = ((Number) created.getBody().get("id")).longValue();
        assertEquals(200, http.getForEntity("/api/timetable", String.class).getStatusCode().value());
        http.delete("/api/departments/{id}", departmentId);
    }

    @Test
    void generatesTheConfiguredWeeklyNumberOfPeriodsWithoutOverlappingTheClass() {
        Setup setup = createSetup(2, true, true);
        Map<String, Object> result = timetable.generate(Map.of("classId", setup.classId(),
                "days", List.of("MONDAY"), "timeSlotIds", setup.slotIds(), "replaceExisting", false));

        assertEquals(2, result.get("scheduledPeriods"));
        assertEquals(2, timetable.timetable(setup.classId(), null).size());
        assertEquals(2, timetable.timetable(setup.classId(), null).stream()
                .map(row -> row.get("timeSlotId")).distinct().count());
        Long roomId = ((Number) timetable.timetable(setup.classId(), null).getFirst().get("classroomId")).longValue();
        assertEquals(2, timetable.timetable(null, null, roomId).size());
        assertTrue(timetable.timetable(null, null, roomId + 999).isEmpty());
    }

    @Test
    void reportsImpossibleSchedulesWhenNoAssignedTeacherCanTeachARequiredSubject() {
        Setup setup = createSetup(1, false, true);

        var error = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> timetable.generate(Map.of("classId", setup.classId(), "days", List.of("MONDAY"),
                        "timeSlotIds", setup.slotIds(), "replaceExisting", false)));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        assertTrue(error.getReason().contains("no active assigned teacher"));
    }

    @Test
    void rejectsClassroomCapacityThatIsTooSmall() {
        Setup setup = createSetup(1, true, true);
        management.save("classes", setup.classId(), Map.of("name", "CS A", "semester", 1,
                "section", "A", "studentCount", 45, "courseId", setup.courseId()));

        var error = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> timetable.generate(Map.of("classId", setup.classId(), "days", List.of("MONDAY"),
                        "timeSlotIds", setup.slotIds(), "replaceExisting", false)));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        assertTrue(error.getReason().contains("capacity for 45 students"));
    }

    @Test
    void rejectsOverlappingActiveTimeSlotDefinitions() {
        management.save("time-slots", null, Map.of("label", "Morning", "startTime", "09:00", "endTime", "10:00"));

        var error = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> management.save("time-slots", null,
                        Map.of("label", "Overlap", "startTime", "09:30", "endTime", "10:30")));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    @Test
    void avoidsTeacherClashesWithAlreadyGeneratedClasses() {
        Setup first = createSetup(1, true, true);
        management.save("classrooms", null, Map.of("name", "Room 2", "capacity", 40, "roomType", "STANDARD"));
        timetable.generate(Map.of("classId", first.classId(), "days", List.of("MONDAY"),
                "timeSlotIds", List.of(first.slotIds().getFirst()), "replaceExisting", false));
        Long secondClassId = ((Number) management.save("classes", null,
                Map.of("name", "Batch B", "semester", 1, "section", "B", "studentCount", 30, "courseId", first.courseId())).get("id")).longValue();
        Long secondSubjectId = ((Number) management.save("subjects", null,
                Map.of("name", "Mathematics", "code", "MATH", "departmentId", first.departmentId())).get("id")).longValue();
        management.save("assignments", null, Map.of("teacherId", first.teacherId(), "subjectId", secondSubjectId));
        management.save("requirements", null, Map.of("classId", secondClassId, "subjectId", secondSubjectId, "periodsPerWeek", 1));

        var error = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> timetable.generate(Map.of("classId", secondClassId, "days", List.of("MONDAY"),
                        "timeSlotIds", List.of(first.slotIds().getFirst()), "replaceExisting", false)));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
    }

    @Test
    void avoidsClassroomClashesWhenAnotherTeacherIsAvailable() {
        Setup first = createSetup(1, true, true);
        timetable.generate(Map.of("classId", first.classId(), "days", List.of("MONDAY"),
                "timeSlotIds", List.of(first.slotIds().getFirst()), "replaceExisting", false));
        Long secondClassId = id(management.save("classes", null,
                Map.of("name", "Batch B", "semester", 1, "section", "B", "studentCount", 30, "courseId", first.courseId())));
        Long secondSubjectId = id(management.save("subjects", null,
                Map.of("name", "Mathematics", "code", "MATH", "departmentId", first.departmentId())));
        Long secondTeacherId = id(management.save("teachers", null,
                Map.of("name", "B. Teacher", "email", "teacher2@example.test", "departmentId", first.departmentId())));
        management.save("assignments", null, Map.of("teacherId", secondTeacherId, "subjectId", secondSubjectId));
        management.save("requirements", null, Map.of("classId", secondClassId, "subjectId", secondSubjectId, "periodsPerWeek", 1));

        var error = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> timetable.generate(Map.of("classId", secondClassId, "days", List.of("MONDAY"),
                        "timeSlotIds", List.of(first.slotIds().getFirst()), "replaceExisting", false)));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
    }

    @Test
    void reportsUnavailableTeachersMissingAssignmentsAndRequirementShortfalls() {
        Setup setup = createSetup(1, true, true);
        timetable.generate(Map.of("classId", setup.classId(), "days", List.of("MONDAY"),
                "timeSlotIds", setup.slotIds(), "replaceExisting", false));
        Map<String, Object> scheduled = timetable.timetable(setup.classId(), null).getFirst();

        Map<String, Object> assignment = management.list("assignments").getFirst();
        management.delete("assignments", ((Number) assignment.get("id")).longValue());
        Map<String, Object> requirement = management.list("requirements").getFirst();
        management.save("requirements", ((Number) requirement.get("id")).longValue(),
                Map.of("classId", setup.classId(), "subjectId", scheduled.get("subjectId"), "periodsPerWeek", 2));
        management.save("teacher-availability", null, Map.of(
                "teacherId", scheduled.get("teacherId"), "dayOfWeek", scheduled.get("dayOfWeek").toString(),
                "timeSlotId", scheduled.get("timeSlotId"), "available", false));

        List<?> issues = (List<?>) timetableController.conflicts().get("items");
        List<String> issueTypes = issues.stream()
                .map(item -> (String) ((Map<?, ?>) item).get("type")).toList();
        assertTrue(issueTypes.contains("Teacher unavailable"));
        assertTrue(issueTypes.contains("Missing teacher assignment"));
        assertTrue(issueTypes.contains("Subject requirement mismatch"));
    }

    private Setup createSetup(int periods, boolean withAssignment, boolean twoSlots) {
        Long departmentId = id(management.save("departments", null, Map.of("name", "Engineering", "code", "ENG")));
        Long courseId = id(management.save("courses", null, Map.of("name", "Computer Science", "code", "CS",
                "semesterCount", 8, "departmentId", departmentId)));
        Long classId = id(management.save("classes", null, Map.of("name", "CS A", "semester", 1,
                "section", "A", "studentCount", 30, "courseId", courseId)));
        Long teacherId = id(management.save("teachers", null, Map.of("name", "A. Teacher", "email", "teacher@example.test",
                "departmentId", departmentId)));
        Long subjectId = id(management.save("subjects", null, Map.of("name", "Algorithms", "code", "ALG",
                "departmentId", departmentId)));
        management.save("classrooms", null, Map.of("name", "Room 1", "capacity", 40, "roomType", "STANDARD"));
        if (withAssignment) management.save("assignments", null, Map.of("teacherId", teacherId, "subjectId", subjectId));
        management.save("requirements", null, Map.of("classId", classId, "subjectId", subjectId, "periodsPerWeek", periods));
        List<Long> slotIds = new ArrayList<>();
        slotIds.add(id(management.save("time-slots", null, Map.of("label", "Period 1",
                "startTime", "09:00", "endTime", "10:00"))));
        if (twoSlots) slotIds.add(id(management.save("time-slots", null, Map.of("label", "Period 2",
                "startTime", "10:00", "endTime", "11:00"))));
        return new Setup(departmentId, courseId, classId, teacherId, slotIds);
    }

    private static Long id(Map<String, Object> value) {
        return ((Number) value.get("id")).longValue();
    }

    private record Setup(Long departmentId, Long courseId, Long classId, Long teacherId, List<Long> slotIds) {}
}
