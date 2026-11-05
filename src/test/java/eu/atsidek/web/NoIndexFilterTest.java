package eu.atsidek.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class NoIndexFilterTest {

    @Test
    void setsNoIndexHeader() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        new NoIndexFilter().doFilter(new MockHttpServletRequest("GET", "/"), res, new MockFilterChain());
        assertEquals("noindex, nofollow", res.getHeader("X-Robots-Tag"));
    }
}
