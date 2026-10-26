package lt.atsidek.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CanonicalHostFilterTest {

    private MockHttpServletResponse run(String canonical, String host, String uri, String query) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setServerName(host);
        req.setQueryString(query);
        MockHttpServletResponse res = new MockHttpServletResponse();
        new CanonicalHostFilter(canonical).doFilter(req, res, new MockFilterChain());
        return res;
    }

    @Test
    void wwwRedirectsToCanonicalHost() throws Exception {
        MockHttpServletResponse res = run("atsidek.eu", "www.atsidek.eu", "/privatumas", "a=1");
        assertEquals(301, res.getStatus());
        assertEquals("https://atsidek.eu/privatumas?a=1", res.getHeader("Location"));
    }

    @Test
    void canonicalHostAndDisabledFilterPassThrough() throws Exception {
        assertNull(run("atsidek.eu", "atsidek.eu", "/", null).getHeader("Location"));
        assertNull(run("", "www.atsidek.eu", "/", null).getHeader("Location"));
    }
}
