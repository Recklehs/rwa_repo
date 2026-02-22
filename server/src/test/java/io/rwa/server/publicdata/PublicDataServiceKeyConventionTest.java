package io.rwa.server.publicdata;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.web3j.crypto.Hash;

class PublicDataServiceKeyConventionTest {

    private final PublicDataService service = new PublicDataService(null, null, null, null, null);

    @Test
    void deriveUnitIdUsesPipeDelimiter() {
        assertThat(service.deriveUnitId("KAPT001", "MPAREA_LE_60", 1))
            .isEqualTo("KAPT001|MPAREA_LE_60|00001");
    }

    @Test
    void deriveClassIdUsesLegacyHashBasisForMigratedBuckets() {
        String classId = service.deriveClassId("KAPT001", "MPAREA_LE_60");
        String expected = Hash.sha3String("kapt001:mparea_60");
        assertThat(classId).isEqualTo(expected);
    }

    @Test
    void deriveClassIdUsesClassKeyDirectlyForNonMigratedBuckets() {
        String classId = service.deriveClassId("KAPT001", "MPAREA_UNKNOWN");
        String expected = Hash.sha3String("kapt001:mparea_unknown");
        assertThat(classId).isEqualTo(expected);
    }
}
