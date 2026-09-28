package io.github.mesmerprism.rustyquest.spatial_camera_panel
import com.meta.spatial.core.*
import com.meta.spatial.runtime.*
internal class Owners {
 val layer=SceneQuadLayer();val objectOwner=SceneObject();val mesh=SceneMesh();val material=SceneMaterial();val swapchain=SceneSwapchain();val anchor=Entity();var entity:Entity?=anchor
 val resources=SpatialSdkQuadResourceCoordinator(SpatialSdkQuadResourceBindings(Scene(),{}, {entity=null}))
 init{resources.adoptSwapchain(swapchain);resources.registerLayer(layer);resources.registerSceneObject(objectOwner);resources.registerAnchor(material,mesh)}
}
fun main(){
 android.os.Looper.setMain(true)
 val stuck=Owners();stuck.layer.reject=true
 check(stuck.resources.cleanup("fixture-first")=="incomplete");check(stuck.layer.calls==1);check(stuck.objectOwner.calls==0);check(!stuck.mesh.destroyed && !stuck.material.destroyed && !stuck.swapchain.destroyed)
 try{stuck.resources.adoptSwapchain(SceneSwapchain());error("uncertain new owner accepted")}catch(expected:IllegalStateException){}
 try{stuck.resources.adoptSurface(android.view.Surface());error("uncertain new surface accepted")}catch(expected:IllegalStateException){}
 stuck.layer.reject=false;check(stuck.resources.cleanup("fixture-retry")=="incomplete");check(stuck.layer.calls==1);check(stuck.entity===stuck.anchor)
 val blocked=CleanupActivityFixture(stuck.resources,stuck.entity);try{blocked.newLaunch();error("failed Stop replaced old owner")}catch(expected:IllegalStateException){check(expected.message=="SDK projection cleanup remains Pending before new launch")};check(blocked.cameraHwbProjectionEntity===stuck.anchor);check(blocked.spatialVideoProjectionRuntimeCoordinator.adopted==0 && blocked.cameraHwbProjectionRawCarrierCoordinator.starts==0)
 var viewerX=1f;var nativeCalls=0;var panelCalls=0
 val updater=SpatialCameraHwbProjectionPlacementUpdateCoordinator(SpatialCameraHwbProjectionPlacementUpdateBindings(stuck.resources,{_,_->}, {false}, {stuck.entity}, {false}, {CameraHwbProjectionPlane(Pose(Vector3(viewerX,0f,0f)))}, {_,_->panelCalls++;"fixture"}, {0}, {SpatialCameraHwbProjectionPlacementNativeState(true,"fixture")}, {nativeCalls++;1L}, {"fixture"}, {"fixture"}, {"fixture"}, {}))
 updater.update("fixture-pending",false);viewerX=2f;updater.update("fixture-movement",false);check(stuck.anchor.pose!!.t.x==2f);check(nativeCalls==0 && panelCalls==0)
 val objectStuck=Owners();objectStuck.objectOwner.reject=true;check(objectStuck.resources.cleanup("fixture-object")=="incomplete");check(objectStuck.objectOwner.calls==1);objectStuck.objectOwner.reject=false;check(objectStuck.resources.cleanup("fixture-object-retry")=="incomplete");check(objectStuck.objectOwner.calls==1);check(!objectStuck.mesh.destroyed)
 for(kind in 0..2){val failed=Owners();when(kind){0->failed.mesh.reject=true;1->failed.material.reject=true;else->failed.swapchain.reject=true};check(failed.resources.cleanup("fixture-dependent-first")=="incomplete");failed.mesh.reject=false;failed.material.reject=false;failed.swapchain.reject=false;check(failed.resources.cleanup("fixture-dependent-retry")=="incomplete");check(when(kind){0->failed.mesh.calls;1->failed.material.calls;else->failed.swapchain.calls}==1)}
 val healthy=Owners();val activity=CleanupActivityFixture(healthy.resources,healthy.entity);check(activity.cleanup("fixture-ui-direct")=="destroyed");check(activity.cameraHwbProjectionRawCarrierCoordinator.removed==1);check(activity.cameraHwbProjectionEntity==null);check(healthy.mesh.destroyed && healthy.material.destroyed && healthy.swapchain.destroyed)
 val workerOwners=Owners();val worker=CleanupActivityFixture(workerOwners.resources,workerOwners.entity);var outcome:String?=null;var failure:Throwable?=null
 val thread=Thread{try{outcome=worker.cleanup("fixture-worker")}catch(e:Throwable){failure=e}};thread.start();val work=worker.queue.poll(5,java.util.concurrent.TimeUnit.SECONDS)?:error("missing UI dispatch");work();thread.join(5000);check(!thread.isAlive && failure==null && outcome=="destroyed");check(workerOwners.layer.calls==1)
 val staleOwners=Owners();val stale=CleanupActivityFixture(staleOwners.resources,staleOwners.entity);failure=null
 val staleThread=Thread{try{stale.cleanup("fixture-stale")}catch(e:Throwable){failure=e}};staleThread.start();val staleWork=stale.queue.poll(5,java.util.concurrent.TimeUnit.SECONDS)?:error("missing stale dispatch");staleOwners.resources.registerLayer(SceneQuadLayer());staleWork();staleThread.join(5000);check(failure is java.util.concurrent.ExecutionException);check(staleOwners.layer.calls==0 && stale.cameraHwbProjectionRawCarrierCoordinator.removed==0)
 val timedOwners=Owners();val timed=CleanupActivityFixture(timedOwners.resources,timedOwners.entity);failure=null
 val timedThread=Thread{try{timed.cleanup("fixture-timeout")}catch(e:Throwable){failure=e}};timedThread.start();val delayed=timed.queue.poll(5,java.util.concurrent.TimeUnit.SECONDS)?:error("missing timed dispatch");timedThread.join(15000);check(!timedThread.isAlive && failure is java.util.concurrent.TimeoutException);timedOwners.resources.registerLayer(SceneQuadLayer());delayed();check(timedOwners.layer.calls==0 && timed.cameraHwbProjectionRawCarrierCoordinator.removed==0)
 println("PASS actual resource/placement/Activity UI helper: sticky layer/object failure never terminal; dependencies and moving viewer pose retained; inactive native calls skipped; UI direct/worker/stale/cancelled timeout fenced; healthy cleanup terminal. SDK/platform boundaries mocked, no physical proof")
}
