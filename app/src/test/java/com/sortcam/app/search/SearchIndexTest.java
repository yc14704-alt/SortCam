package com.sortcam.app.search;
import com.sortcam.app.model.Category;
import com.sortcam.app.model.PhotoRecord;
import org.junit.Test;
import static org.junit.Assert.*;
public class SearchIndexTest {
    private final Category category = new Category(1, "업무", "", true, 0);
    private final PhotoRecord photo = new PhotoRecord(1, "content://photo/1", 1, 0,
            "#사진 #설치", "인천 현장", "계약서 010-1234-5678 송장 ABC987654", "", "photo", 0);
    @Test public void singleTermAndPhoneNormalization() {
        assertTrue(SearchIndex.matches(photo, category, "01012345678"));
        assertTrue(SearchIndex.matches(photo, category, "ＡＢＣ９８７６５４"));
    }
    @Test public void allTermsAcrossCategoryMemoAndOcrInAnyOrder() {
        assertTrue(SearchIndex.matches(photo, category, "987654 업무 인천"));
        assertTrue(SearchIndex.matches(photo, category, "계약서, 설치，01012345678"));
        assertFalse(SearchIndex.matches(photo, category, "업무 부산"));
    }
    @Test public void anyTermsUnionAndNoMatch() {
        assertTrue(SearchIndex.matches(photo, category, "부산,계약서", true));
        assertFalse(SearchIndex.matches(photo, category, "부산 영수증", true));
        assertFalse(SearchIndex.matches(photo, category, "부산 영수증", false));
    }
    @Test public void emptyAndPunctuationHaveNoRestriction() {
        assertTrue(SearchIndex.matches(photo, category, ""));
        assertTrue(SearchIndex.matches(photo, category, " , ; # ", true));
    }
    @Test public void mediaTypeIsSearchable() {
        assertTrue(SearchIndex.matches(photo, category, "사진 계약서"));
        assertFalse(SearchIndex.matches(photo, category, "동영상 계약서"));
    }
}
