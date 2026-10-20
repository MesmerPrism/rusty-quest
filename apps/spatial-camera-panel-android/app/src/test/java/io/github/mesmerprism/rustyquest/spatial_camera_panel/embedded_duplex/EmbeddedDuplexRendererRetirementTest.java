package io.github.mesmerprism.rustyquest.spatial_camera_panel.embedded_duplex;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual Java closed consumer; native owner facts are explicitly modeled. */
public final class EmbeddedDuplexRendererRetirementTest {
    private JSONObject pristine()throws Exception{
        return new JSONObject().put("$schema","rusty.quest.embedded_duplex.renderer_retirement_status.v1")
            .put("state","never_created").put("construction_attempts",0).put("active_workers",0)
            .put("claimed_route_generation",JSONObject.NULL).put("worker_state",JSONObject.NULL)
            .put("cleanup_code",0).put("carrier_live",false).put("pending_frame",false);
    }
    @Test public void actualNeverCreatedNeedsMonotonicConstructionAbsence()throws Exception{
        JSONObject p=pristine();assertTrue(EmbeddedDuplexRendererRetirement.terminal(p));
        p.put("construction_attempts",1);
        try{EmbeddedDuplexRendererRetirement.terminal(p);fail("cleanup0 after construction is not NeverCreated");}catch(IllegalStateException expected){}
    }
    @Test public void zeroDiagnosticHoldsCannotCreditRejectedCleanup()throws Exception{
        JSONObject p=pristine().put("state","cleanup_rejected").put("cleanup_code",3).put("construction_attempts",1);
        assertFalse(EmbeddedDuplexRendererRetirement.terminal(p));
    }
    @Test public void genuineRetiredClassificationStillRequiresWorkerJoin()throws Exception{
        JSONObject p=pristine().put("state","retired").put("cleanup_code",2).put("construction_attempts",1);
        assertTrue(EmbeddedDuplexRendererRetirement.terminal(p));p.put("worker_state","finished_unjoined");
        try{EmbeddedDuplexRendererRetirement.terminal(p);fail("finished worker is not a joined worker");}catch(IllegalStateException expected){}
    }
    @Test public void actualClaimOrCarrierCannotBeErasedByTerminalLabel()throws Exception{
        JSONObject p=pristine().put("state","retired").put("cleanup_code",2).put("construction_attempts",1).put("claimed_route_generation",5);
        try{EmbeddedDuplexRendererRetirement.terminal(p);fail("actual claim remains");}catch(IllegalStateException expected){}
        p.put("claimed_route_generation",JSONObject.NULL).put("carrier_live",true);
        try{EmbeddedDuplexRendererRetirement.terminal(p);fail("actual carrier remains");}catch(IllegalStateException expected){}
    }
    @Test public void extraFieldsAndInventedAbsenceAreNotAccepted()throws Exception{
        JSONObject p=pristine().put("diagnostic_only",true);
        try{EmbeddedDuplexRendererRetirement.terminal(p);fail("closed schema");}catch(IllegalArgumentException expected){}
        p=pristine().put("state","pending_or_unproven");assertFalse(EmbeddedDuplexRendererRetirement.terminal(p));
    }
    private JSONObject fresh(String state)throws Exception{
        return new JSONObject().put("$schema","rusty.quest.embedded_duplex.renderer_fresh_setup_eligibility.v1")
            .put("state",state).put("own_process_generation",11).put("own_source_generation",7)
            .put("construction_attempts",1).put("active_workers",1).put("peer_source_inactive",true)
            .put("retained_peer_payloads",0).put("cleanup_code",0).put("carrier_live",true);
    }
    @Test public void liveOwnUsesIndependentNativeOwnerWitnessNotWholeRetirement()throws Exception{
        assertTrue(EmbeddedDuplexRendererRetirement.freshEligible(fresh("own_live_independent"),"live_independent",11));
        assertFalse(EmbeddedDuplexRendererRetirement.freshEligible(fresh("own_live_independent"),"retired",11));
    }
    @Test public void ownLiveCannotHideActualRetainedPeerPayloadOrSource()throws Exception{
        JSONObject p=fresh("own_live_independent").put("retained_peer_payloads",1);
        try{EmbeddedDuplexRendererRetirement.freshEligible(p,"live_independent",11);fail("Peer payload still retained");}catch(IllegalStateException expected){}
        p=fresh("own_live_independent").put("peer_source_inactive",false);
        try{EmbeddedDuplexRendererRetirement.freshEligible(p,"live_independent",11);fail("Peer source still selected");}catch(IllegalStateException expected){}
    }
    @Test public void wrongOwnEpochAppOrUnjoinedWorkerRefusesFreshEligibility()throws Exception{
        try{EmbeddedDuplexRendererRetirement.freshEligible(fresh("own_live_independent"),"live_independent",12);fail("app generation differs");}catch(IllegalStateException expected){}
        JSONObject p=fresh("own_live_independent").put("active_workers",0);
        try{EmbeddedDuplexRendererRetirement.freshEligible(p,"live_independent",11);fail("no actual ready worker");}catch(IllegalStateException expected){}
        assertFalse(EmbeddedDuplexRendererRetirement.freshEligible(fresh("pending_or_unproven"),"live_independent",11));
    }
    @Test public void terminalFreshBranchDoesNotTurnOwnLiveIntoCleanup()throws Exception{
        JSONObject p=fresh("terminal").put("active_workers",0).put("carrier_live",false).put("cleanup_code",2);
        assertTrue(EmbeddedDuplexRendererRetirement.freshEligible(p,"retired",11));
        assertFalse(EmbeddedDuplexRendererRetirement.freshEligible(p,"live_independent",11));
        p.put("state","cleanup_rejected").put("cleanup_code",3);
        assertFalse(EmbeddedDuplexRendererRetirement.freshEligible(p,"retired",11));
    }
}
