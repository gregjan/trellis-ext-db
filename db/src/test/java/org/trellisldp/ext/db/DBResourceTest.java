/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.trellisldp.ext.db;

import static java.io.File.separator;
import static java.time.Instant.now;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.condition.OS.WINDOWS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.slf4j.LoggerFactory.getLogger;
import static org.trellisldp.api.RDFUtils.TRELLIS_DATA_PREFIX;
import static org.trellisldp.api.RDFUtils.getInstance;
import static org.trellisldp.api.Resource.SpecialResources.MISSING_RESOURCE;
import static org.trellisldp.vocabulary.RDF.type;

import com.opentable.db.postgres.embedded.EmbeddedPostgres;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletionException;

import org.apache.commons.rdf.api.Dataset;
import org.apache.commons.rdf.api.Graph;
import org.apache.commons.rdf.api.IRI;
import org.apache.commons.rdf.api.RDF;
import org.apache.commons.text.RandomStringGenerator;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.slf4j.Logger;
import org.trellisldp.api.Binary;
import org.trellisldp.api.IdentifierService;
import org.trellisldp.api.ResourceService;
import org.trellisldp.id.UUIDGenerator;
import org.trellisldp.vocabulary.ACL;
import org.trellisldp.vocabulary.DC;
import org.trellisldp.vocabulary.FOAF;
import org.trellisldp.vocabulary.LDP;
import org.trellisldp.vocabulary.OA;
import org.trellisldp.vocabulary.Trellis;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * ResourceService tests.
 */
@DisabledOnOs(WINDOWS)
public class DBResourceTest {

    private static final Logger LOGGER = getLogger(DBResourceTest.class);
    private static final RDF rdf = getInstance();

    private static final IdentifierService idService = new UUIDGenerator();

    private static final IRI root = rdf.createIRI(TRELLIS_DATA_PREFIX);

    private static EmbeddedPostgres pg = null;

    private static ResourceService svc = null;

    static {
        try {
            pg = EmbeddedPostgres.builder()
                .setDataDirectory("build" + separator + "pgdata-" + new RandomStringGenerator
                            .Builder().withinRange('a', 'z').build().generate(10)).start();

            // Set up database migrations
            try (final Connection c = pg.getPostgresDatabase().getConnection()) {
                final Liquibase liquibase = new Liquibase("migrations.yml",
                        new ClassLoaderResourceAccessor(),
                        new JdbcConnection(c));
                final Contexts ctx = null;
                liquibase.update(ctx);
            }

            svc = new DBResourceService(pg.getPostgresDatabase(), idService);

        } catch (final IOException | SQLException | LiquibaseException ex) {
            LOGGER.error("Error setting up tests", ex);
        }
    }

    @Test
    public void getRoot() {
        assertEquals(root, DBResource.findResource(pg.getPostgresDatabase(), root).join().getIdentifier(),
                "Check the root resource");
    }

    @Test
    public void testReinit() {
        final ResourceService svc2 = new DBResourceService(pg.getPostgresDatabase(), idService);
        assertNotNull(svc2);
    }

    @Test
    public void getNonExistent() {
        assertEquals(MISSING_RESOURCE, DBResource.findResource(pg.getPostgresDatabase(),
                    rdf.createIRI(TRELLIS_DATA_PREFIX + "other")).join(), "Check for non-existent resource");
    }

    @Test
    public void getServerQuads() {
        assertAll(() ->
            DBResource.findResource(pg.getPostgresDatabase(), root).thenAccept(res -> {
                final Graph serverManaged = rdf.createGraph();
                res.stream(Trellis.PreferServerManaged).forEach(serverManaged::add);
                assertTrue(serverManaged.contains(root, type, LDP.BasicContainer));
            }).join());
    }

    @Test
    public void getMembershipQuads() {
        assertAll(() ->
            DBResource.findResource(pg.getPostgresDatabase(), root).thenAccept(res ->
                assertEquals(0L, res.stream(LDP.PreferMembership).count())).join());
    }

    @Test
    public void getBinary() throws Exception {
        final IRI identifier = rdf.createIRI(TRELLIS_DATA_PREFIX + "binary");
        final IRI binaryIri = rdf.createIRI("http://example.com/resource");
        final Dataset dataset = rdf.createDataset();
        dataset.add(Trellis.PreferServerManaged, identifier, DC.isPartOf, root);
        final Binary binary = new Binary(binaryIri, now(), "text/plain", 10L);
        assertNull(svc.create(identifier, LDP.NonRDFSource, dataset, root, binary).join());
        svc.get(identifier).thenAccept(res -> {
            assertTrue(res.getBinary().isPresent());
            assertTrue(res.stream(Trellis.PreferServerManaged).anyMatch(triple ->
                    triple.getSubject().equals(identifier) && triple.getPredicate().equals(DC.hasPart) &&
                    triple.getObject().equals(binaryIri)));
        }).join();
    }

    @Test
    public void getRootContent() throws Exception {
        final Dataset dataset = rdf.createDataset();
        dataset.add(Trellis.PreferUserManaged, root, DC.title, rdf.createLiteral("A title", "eng"));
        assertNull(svc.replace(root, LDP.BasicContainer, dataset, null, null).join());
        svc.get(root).thenAccept(res -> {
            assertEquals(LDP.BasicContainer, res.getInteractionModel());
            assertFalse(res.stream(Trellis.PreferServerManaged).anyMatch(triple ->
                    triple.getSubject().equals(root) && triple.getPredicate().equals(DC.isPartOf)));
            assertTrue(res.stream(Trellis.PreferUserManaged).anyMatch(triple ->
                    triple.getSubject().equals(root) && triple.getPredicate().equals(DC.title)
                    && triple.getObject().equals(rdf.createLiteral("A title", "eng"))));
        }).join();
    }

    @Test
    public void getAclQuads() {
        assertAll(() ->
            DBResource.findResource(pg.getPostgresDatabase(), root).thenAccept(res -> {
                final Graph acl = rdf.createGraph();
                res.stream(Trellis.PreferAccessControl).forEach(acl::add);
                assertTrue(acl.contains(null, ACL.mode, ACL.Read));
                assertTrue(acl.contains(null, ACL.mode, ACL.Write));
                assertTrue(acl.contains(null, ACL.mode, ACL.Control));
            }).join());
    }

    @Test
    public void testAuthQuads() throws Exception {
        final IRI identifier = rdf.createIRI(TRELLIS_DATA_PREFIX + "auth#acl");
        final Dataset dataset = rdf.createDataset();
        dataset.add(Trellis.PreferAccessControl, identifier, ACL.mode, ACL.Read);
        dataset.add(Trellis.PreferAccessControl, identifier, ACL.mode, ACL.Write);
        dataset.add(Trellis.PreferAccessControl, identifier, ACL.mode, ACL.Control);
        dataset.add(Trellis.PreferAccessControl, identifier, ACL.mode, ACL.Append);
        dataset.add(Trellis.PreferAccessControl, identifier, ACL.agentClass, FOAF.Agent);
        dataset.add(Trellis.PreferAccessControl, identifier, ACL.accessTo,
                rdf.createIRI(TRELLIS_DATA_PREFIX + "auth"));

        assertNull(svc.create(identifier, LDP.RDFSource, dataset, root, null).join());
        svc.get(identifier).thenAccept(res -> {
            assertEquals(6L, res.stream(Trellis.PreferAccessControl).count());
        }).join();
    }

    @Test
    public void testEmptyAudit() throws Exception {
        final IRI identifier = rdf.createIRI(TRELLIS_DATA_PREFIX + idService.getSupplier().get());

        assertNull(svc.create(identifier, LDP.RDFSource, rdf.createDataset(), root, null).join());
        assertNull(svc.add(identifier, rdf.createDataset()).join());
        svc.get(identifier).thenAccept(res -> assertEquals(0L, res.stream(Trellis.PreferAudit).count())).join();
    }

    @Test
    public void getExtraLinkRelations() throws Exception {
        final IRI identifier = rdf.createIRI(TRELLIS_DATA_PREFIX + "extras");
        final String inbox = "http://example.com/inbox";
        final String annotations = "http://example.com/annotations";
        final Dataset dataset = rdf.createDataset();
        dataset.add(Trellis.PreferUserManaged, identifier, LDP.inbox, rdf.createIRI(inbox));
        dataset.add(Trellis.PreferUserManaged, identifier, OA.annotationService, rdf.createIRI(annotations));
        assertNull(svc.create(identifier, LDP.RDFSource, dataset, root, null).join());
        svc.get(identifier).thenAccept(res -> {
            assertTrue(res.stream(Trellis.PreferUserManaged).anyMatch(triple ->
                    triple.getSubject().equals(identifier) && triple.getPredicate().equals(LDP.inbox) &&
                    triple.getObject().equals(rdf.createIRI(inbox))));
        }).join();
        DBResource.findResource(pg.getPostgresDatabase(), identifier).thenAccept(res -> {
            assertEquals(2L, res.getExtraLinkRelations().count());
            assertTrue(res.getExtraLinkRelations().anyMatch(rel -> rel.getKey().equals(annotations)
                        && rel.getValue().equals(OA.annotationService.getIRIString())));
            assertTrue(res.getExtraLinkRelations().anyMatch(rel -> rel.getKey().equals(inbox)
                        && rel.getValue().equals(LDP.inbox.getIRIString())));
        }).join();
    }

    @Test
    public void testAddErrorCondition() throws Exception {
        final IRI identifier = rdf.createIRI(TRELLIS_DATA_PREFIX + "resource");
        final Dataset dataset = rdf.createDataset();
        dataset.add(Trellis.PreferAudit, rdf.createBlankNode(), type, rdf.createLiteral("Invalid quad"));
        assertThrows(CompletionException.class, () -> svc.add(identifier, dataset).join());
    }

    @Test
    public void testDeleteErrorCondition() throws Exception {
        final IRI identifier = rdf.createIRI(TRELLIS_DATA_PREFIX + "resource");
        final Jdbi mockJdbi = mock(Jdbi.class);
        doThrow(RuntimeException.class).when(mockJdbi).useTransaction(any());

        final ResourceService svc2 = new DBResourceService(mockJdbi, idService);
        assertThrows(CompletionException.class, () -> svc2.delete(identifier, null).join(),
                "No exception with invalid connection!");
    }

    @Test
    public void testCreateErrorCondition() throws Exception {
        final IRI identifier = rdf.createIRI(TRELLIS_DATA_PREFIX + "resource");
        assertThrows(CompletionException.class, () ->
                svc.create(identifier, null, null, null, null).join());
    }

    @Test
    public void testCreateErrorCondition2() throws Exception {
        final IRI identifier = rdf.createIRI(TRELLIS_DATA_PREFIX + "resource");
        final Dataset dataset = rdf.createDataset();
        final Jdbi mockJdbi = mock(Jdbi.class);
        final ResourceService svc2 = new DBResourceService(mockJdbi, idService);
        doThrow(RuntimeException.class).when(mockJdbi).useTransaction(any());

        assertThrows(CompletionException.class, () ->
                svc2.create(identifier, LDP.Container, dataset, null, null).join());
    }
}
