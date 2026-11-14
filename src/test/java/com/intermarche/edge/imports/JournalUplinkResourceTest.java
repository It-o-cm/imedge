package com.intermarche.edge.imports;

import com.intermarche.edge.service.deploy.JournalUplinkService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link JournalUplinkResource}. */
class JournalUplinkResourceTest {

    /**
     * Builds the resource.
     *
     * @param role the node's role
     * @param token the shared token, possibly null
     * @return the resource
     */
    private JournalUplinkResource resource(String role, String token) {
        JournalUplinkResource resource = new JournalUplinkResource();
        resource.role = role;
        resource.token = Optional.ofNullable(token);
        resource.journalUplinkService = mock(JournalUplinkService.class);
        return resource;
    }

    /** BO-02-05-05: the central node answers the watermark and files the batches. */
    @Test
    void theCentralAnswersAndFiles() {
        JournalUplinkResource open = resource("central", null);
        JournalUplinkService.Answer mark = new JournalUplinkService.Answer();
        when(open.journalUplinkService.mark("10001")).thenReturn(mark);
        Response marked = open.mark(null, "10001");
        assertEquals(200, marked.getStatus());
        assertSame(mark, marked.getEntity());
        JournalUplinkService.Batch batch = new JournalUplinkService.Batch();
        batch.pdvNumber = "10001";
        JournalUplinkService.Answer filed = new JournalUplinkService.Answer();
        when(open.journalUplinkService.receive(batch)).thenReturn(filed);
        assertSame(filed, open.receive(null, batch).getEntity());
        JournalUplinkResource blank = resource("CENTRAL", " ");
        assertEquals(200, blank.receive("x", null).getStatus());
        verify(blank.journalUplinkService).receive(null);
        JournalUplinkResource gated = resource("central", "s3cret");
        assertEquals(200, gated.mark("s3cret", "10001").getStatus());
    }

    /** Only the central node holds the journal, behind the shared token. */
    @Test
    void otherNodesAndWrongTokensAreRefused() {
        JournalUplinkResource store = resource("store", null);
        assertEquals(403, store.mark(null, "10001").getStatus());
        assertEquals(403, store.receive(null, new JournalUplinkService.Batch()).getStatus());
        JournalUplinkResource gated = resource("central", "s3cret");
        assertEquals(401, gated.mark("wrong", "10001").getStatus());
        assertEquals(401, gated.receive(null, new JournalUplinkService.Batch()).getStatus());
        verify(store.journalUplinkService, never()).mark("10001");
        verify(gated.journalUplinkService, never()).mark("10001");
    }
}
