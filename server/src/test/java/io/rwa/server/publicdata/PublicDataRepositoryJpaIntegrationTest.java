package io.rwa.server.publicdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(properties = {
    "spring.flyway.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:rwa-jpa-publicdata;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
    "spring.datasource.driverClassName=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional
class PublicDataRepositoryJpaIntegrationTest {

    @Autowired
    private UnitRepository unitRepository;

    @Autowired
    private PropertyClassRepository propertyClassRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("UnitRepository는 classId 기준 unitNo 오름차순으로 조회한다")
    void unitRepositoryShouldReturnUnitsInAscendingOrder() {
        // given: 같은 classId에 unitNo 순서가 섞인 데이터를 저장한다.
        saveUnit("unit-3", "class-1", 3, BigInteger.valueOf(1003), "IMPORTED");
        saveUnit("unit-1", "class-1", 1, BigInteger.valueOf(1001), "IMPORTED");
        saveUnit("unit-2", "class-1", 2, BigInteger.valueOf(1002), "IMPORTED");
        entityManager.flush();
        entityManager.clear();

        // when: classId 기준 정렬 조회를 실행한다.
        List<UnitEntity> units = unitRepository.findByClassIdOrderByUnitNoAsc("class-1");

        // then: unitNo가 1,2,3 순서로 반환된다.
        assertThat(units).hasSize(3);
        assertThat(units).extracting(UnitEntity::getUnitNo).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("UnitRepository는 classId와 unitNo로 특정 유닛을 조회한다")
    void unitRepositoryShouldFindByClassIdAndUnitNo() {
        // given: classId/unitNo 조합으로 식별 가능한 유닛을 저장한다.
        saveUnit("unit-a", "class-find", 7, new BigInteger("777777777777777777"), "TOKENIZED");
        entityManager.flush();
        entityManager.clear();

        // when: classId와 unitNo로 유닛을 조회한다.
        UnitEntity found = unitRepository.findByClassIdAndUnitNo("class-find", 7).orElseThrow();

        // then: 저장된 tokenId/status가 정확히 조회된다.
        assertThat(found.getUnitId()).isEqualTo("unit-a");
        assertThat(found.getTokenId()).isEqualTo(new BigInteger("777777777777777777"));
        assertThat(found.getStatus()).isEqualTo("TOKENIZED");
    }

    @Test
    @DisplayName("UnitRepository는 tokenId가 있는 유닛만 tokenId 오름차순으로 조회한다")
    void unitRepositoryShouldReturnOnlyIssuedUnitsInTokenOrder() {
        // given: tokenId가 null인 유닛과 발행된 유닛을 함께 저장한다.
        saveUnit("unit-null", "class-issued", 1, null, "IMPORTED");
        saveUnit("unit-200", "class-issued", 2, BigInteger.valueOf(200), "TOKENIZED");
        saveUnit("unit-100", "class-issued", 3, BigInteger.valueOf(100), "TOKENIZED");
        entityManager.flush();
        entityManager.clear();

        // when: 발행 완료(tokenId not null) 유닛 목록을 조회한다.
        List<UnitEntity> issuedUnits = unitRepository.findByTokenIdIsNotNullOrderByTokenIdAsc();

        // then: tokenId가 있는 유닛만 오름차순으로 반환된다.
        assertThat(issuedUnits).hasSize(2);
        assertThat(issuedUnits).extracting(UnitEntity::getUnitId).containsExactly("unit-100", "unit-200");
        assertThat(issuedUnits).extracting(UnitEntity::getTokenId)
            .containsExactly(BigInteger.valueOf(100), BigInteger.valueOf(200));
    }

    @Test
    @DisplayName("PropertyClassRepository는 kaptCode 기준 classKey 오름차순으로 조회한다")
    void propertyClassRepositoryShouldOrderByClassKey() {
        // given: 동일 kaptCode에 classKey가 다른 클래스를 저장한다.
        saveClass("class-60-85", "KAPT-001", "MPAREA_60_85", 20);
        saveClass("class-le-60", "KAPT-001", "MPAREA_LE_60", 10);
        entityManager.flush();
        entityManager.clear();

        // when: kaptCode 기준 정렬 조회를 실행한다.
        List<PropertyClassEntity> classes = propertyClassRepository.findByKaptCodeOrderByClassKeyAsc("KAPT-001");

        // then: classKey가 사전순(60_85, LE_60)으로 반환된다.
        assertThat(classes).hasSize(2);
        assertThat(classes).extracting(PropertyClassEntity::getClassKey).containsExactly("MPAREA_60_85", "MPAREA_LE_60");
    }

    private void saveUnit(String unitId, String classId, int unitNo, BigInteger tokenId, String status) {
        UnitEntity entity = new UnitEntity();
        entity.setUnitId(unitId);
        entity.setClassId(classId);
        entity.setUnitNo(unitNo);
        entity.setTokenId(tokenId);
        entity.setStatus(status);
        unitRepository.save(entity);
    }

    private void saveClass(String classId, String kaptCode, String classKey, int unitCount) {
        PropertyClassEntity entity = new PropertyClassEntity();
        entity.setClassId(classId);
        entity.setKaptCode(kaptCode);
        entity.setClassKey(classKey);
        entity.setUnitCount(unitCount);
        entity.setStatus("IMPORTED");
        entity.setCreatedAt(Instant.parse("2026-02-17T03:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2026-02-17T03:00:00Z"));
        propertyClassRepository.save(entity);
    }
}
