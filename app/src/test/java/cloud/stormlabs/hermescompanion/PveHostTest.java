package cloud.stormlabs.hermescompanion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

public class PveHostTest {
    @Test public void pveHostProblemsBecomeReasons() throws Exception {
        String json = "{'pve':{'reachable':true,'host':{'node':'pve1','mem_total':100,'mem_avail':50,"
                + "'temps':[{'label':'Package id 0','c':91},{'label':'Composite','c':30}],"
                + "'storage':[{'name':'/vault','type':'zfs','status':'active','total':100,'used':98},"
                + "{'name':'/backup','type':'nfs4','status':'unreachable'},"
                + "{'name':'/','type':'ext4','status':'active','total':100,'used':40}],"
                + "'guests':{'100':'running','101':'stopped'}}}}";
        HubApi.PveHost h = new HubApi.Infra(new JSONObject(json.replace('\'', '"'))).pveHost;
        List<SystemHealth.Reason> r = SystemHealth.pveReasons(h);
        assertEquals(3, r.size());
        assertEquals(SystemHealth.BAD, r.get(0).level);   // /vault 98%
        assertEquals(SystemHealth.WARN, r.get(1).level);  // /backup unreachable
        assertEquals(SystemHealth.BAD, r.get(2).level);   // CPU 91 °C
        assertEquals(1, h.running);
        assertEquals(30, h.tempOf("Composite"), 0.01);
        assertTrue(SystemHealth.pveReasons(null).isEmpty());
    }
}
