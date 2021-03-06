package com.benstopford.coherence.bootstrap.morecomplex;

import com.benstopford.coherence.bootstrap.structures.framework.cluster.ClusterRunner;
import com.benstopford.coherence.bootstrap.structures.framework.cluster.PersistentPortTracker;
import com.tangosol.net.NamedCache;
import com.tangosol.net.messaging.ConnectionException;
import com.tangosol.util.AbstractMapListener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static java.lang.System.*;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;
import static org.junit.Assert.assertFalse;

/**
 * This test shows how updates can be lost if you use simple listeners. Here update 3 will be lost.
 * <p/>
 * To solve this problem simply catch the exception and run a query against the cache to see what changed since you were
 * last updated. this may mean you need to have a last updated field. If so then use and index to make the query faster
 * and make it ordered (as you will be doing a range query)
 * BTS, 25-Jan-2008
 */
public final class ListenersCanLoseData extends ClusterRunner {
    private final List<Object> valuesSentToClient1 = new ArrayList<Object>();
    private final List<Object> valuesSentToClient2 = new ArrayList<Object>();


    @Test
    public void shouldLoseUpdateWhenConnectionProxyGoesDown() throws Exception {

        new PersistentPortTracker().incrementExtendPort("com.benstopford.extend.port2");

        //start one data node and two data-disabled proxies listening on different ports
        startCoherenceProcess("config/basic-cache.xml");
        Process extendProxy1 = startCoherenceProcess("config/basic-extend-enabled-cache-32001.xml", LOCAL_STORAGE_FALSE);
        startCoherenceProcess("config/basic-extend-enabled-cache-32002.xml", LOCAL_STORAGE_FALSE);


        //connect to each of the extend proxies
        NamedCache cacheViaConnection1 = getCacheConnection1();
        NamedCache cacheViaConnection2 = getCacheConnection2();

        //dynamically add listener to connection 1
        cacheViaConnection1.addMapListener(new AbstractMapListener() {
            public void entryInserted(com.tangosol.util.MapEvent mapEvent) {
                out.println("Entry was inserted " + mapEvent.getNewValue());
                valuesSentToClient1.add(mapEvent.getNewValue());
            }

            public void entryUpdated(com.tangosol.util.MapEvent mapEvent) {
                out.println("Entry was udpated to " + mapEvent.getNewValue());
                valuesSentToClient1.add(mapEvent.getNewValue());
            }
        });

        cacheViaConnection2.addMapListener(new AbstractMapListener() {
            public void entryInserted(com.tangosol.util.MapEvent mapEvent) {
                out.println("Entry was inserted " + mapEvent.getNewValue());
                valuesSentToClient2.add(mapEvent.getNewValue());
            }

            public void entryUpdated(com.tangosol.util.MapEvent mapEvent) {
                out.println("Entry was udpated to " + mapEvent.getNewValue());
                valuesSentToClient2.add(mapEvent.getNewValue());
            }
        });

        cacheViaConnection1.put("Foo", "1");
        cacheViaConnection2.put("Foo", "2");
        Thread.sleep(1000);

        //kill the first extend proxy process
        extendProxy1.destroy();
        out.println("waiting for socket to timeout");
        Thread.sleep(40 * 1000);


        //check that connection 1 now throws as exception due to the proxy being dead.
        try {
            cacheViaConnection1.size();
            fail("Connetion should exception should have been thrown as proxy is down");
        } catch (ConnectionException expected) {
        }

        //add entry via the second connection
        cacheViaConnection2.put("Foo", "3"); //this one should be dropped
        Thread.sleep(1000);

        //restart dead extend proxy
        extendProxy1 = startCoherenceProcess("config/basic-extend-enabled-cache-32001.xml", "-Dtangosol.coherence.distributed.localstorage=false ");
        Thread.sleep(1000);

        //add value via restarted extend proxy
        cacheViaConnection1.put("Foo", "4");
        Thread.sleep(1000);

        //check that the listener was called for all but the entry where the connection had been down
        //proving that listeners can loose updates if connections are terminated.
        assertTrue(valuesSentToClient1.contains("1"));
        assertTrue(valuesSentToClient1.contains("2"));
        assertFalse(valuesSentToClient1.contains("3")); //*****THIS UPDATE WAS LOST****
        assertTrue(valuesSentToClient1.contains("4"));

        //check that the connnection that remained did recieve all updates
        assertTrue(valuesSentToClient2.contains("1"));
        assertTrue(valuesSentToClient2.contains("2"));
        assertTrue(valuesSentToClient2.contains("3"));
        assertTrue(valuesSentToClient2.contains("4"));
    }


    private NamedCache getCacheConnection1() {
        return cacheViaExtend();
    }

    private NamedCache getCacheConnection2() {
        return getCache("config/extend-client-32002.xml", "foo");
    }


    @Before
    public void setUp() throws Exception {
        valuesSentToClient1.clear();
        valuesSentToClient2.clear();
        super.setUp();
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }
}
