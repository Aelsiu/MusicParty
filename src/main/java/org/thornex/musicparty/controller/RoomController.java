package org.thornex.musicparty.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.thornex.musicparty.room.*;
import java.util.*;

@RestController @RequestMapping("/api/rooms")
public class RoomController {
    private final RoomRepository repository;
    private final RoomAccessService access;
    private final RoomLifecycleService lifecycle;
    public RoomController(RoomRepository repository,RoomAccessService access,RoomLifecycleService lifecycle) { this.repository=repository;this.access=access;this.lifecycle=lifecycle; }
    private String token(HttpServletRequest request) { return RoomHttpFilter.managementToken(request); }
    @PostMapping("/management/session") public Map<String,Object> login(@RequestBody Map<String,String> body,HttpServletRequest request) { var m=access.login(body.get("key"),request.getRemoteAddr());return Map.of("token",m.token(),"licenseId",m.licenseId(),"root",m.root(),"expiresAt",m.expiresAt()); }
    @GetMapping("/management/session") public Map<String,Object> session(HttpServletRequest request) { var m=access.manager(token(request));return Map.of("licenseId",m.licenseId(),"root",m.root(),"expiresAt",m.expiresAt()); }
    @PostMapping("/join") public Map<String,Object> join(@RequestBody Map<String,String> body,HttpServletRequest request) { var a=access.join(body.get("code"),request.getRemoteAddr());var r=repository.room(a.roomId());return Map.of("room",roomInfo(r,false),"token",a.token(),"expiresAt",a.expiresAt()); }
    @GetMapping("/{id}/admission") public Map<String,Object> resume(@PathVariable String id,@RequestHeader("X-Room-Token") String value) { access.admission(value,id);return Map.of("room",roomInfo(repository.room(id),false),"expiresAt",repository.nextPairingUpdateAt(System.currentTimeMillis())); }
    @GetMapping public Map<String,Object> list(HttpServletRequest request,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        var m=access.manager(token(request));repository.rotate(System.currentTimeMillis());
        return Map.of("rooms",repository.rooms().stream().filter(r->m.root()||r.ownerId().equals(m.licenseId())).map(r->roomInfo(r,true)).toList(),"licenseId",m.licenseId(),"root",m.root(),"quota",9);
    }
    @PostMapping public Map<String,Object> create(@RequestBody Map<String,String> body,HttpServletRequest request) { var m=access.manager(token(request));return roomInfo(repository.create(m.licenseId(),body.get("name"),body.get("requestId")),true); }
    @DeleteMapping("/{id}") public Map<String,String> delete(@PathVariable String id,HttpServletRequest request) { access.own(token(request),id);repository.deleteRoom(id);lifecycle.deleted(id);return Map.of("message","房间已删除"); }
    @GetMapping("/{id}/manage") public Map<String,Object> manage(@PathVariable String id,HttpServletRequest request,HttpServletResponse response) { response.setHeader("Cache-Control","no-store");access.own(token(request),id);repository.rotate(System.currentTimeMillis());return roomInfo(repository.room(id),true); }
    @GetMapping("/{id}/pairing") public Map<String,Object> pairing(@PathVariable String id,HttpServletRequest request,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        access.member(request.getHeader("X-Room-Token"),token(request),id);
        repository.rotate(System.currentTimeMillis());var room=repository.room(id);
        if(!room.pairingOpen()) return Map.of("open",false);
        return Map.of("open",true,"pairingCode",room.pairingCode(),"nextUpdateAt",repository.nextPairingUpdateAt(System.currentTimeMillis()),"serverTime",System.currentTimeMillis(),"pairingIntervalMinutes",repository.pairingIntervalMinutes());
    }
    @PatchMapping("/{id}/pairing") public Map<String,Object> setPairing(@PathVariable String id,@RequestBody Map<String,Boolean> body,HttpServletRequest request,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        access.own(token(request),id);
        Boolean open=body.get("open");if(open==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请提供 OPEN 状态");
        repository.setPairingOpen(id,open);lifecycle.pairingChanged(id);return roomInfo(repository.room(id),true);
    }
    @PostMapping("/{id}/connected") public Map<String,Boolean> connected(@PathVariable String id,HttpServletRequest request) {
        access.own(token(request),id);
        if(access.count(id)==0) throw new ResponseStatusException(HttpStatus.CONFLICT,"房间尚未连接");
        return Map.of("openAdmin",repository.consumeAutoOpen(id));
    }
    @GetMapping("/licenses") public List<Map<String,Object>> licenses(HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response) { access.root(token(request));response.setHeader("Cache-Control","no-store");return repository.licenses().stream().map(l->Map.<String,Object>of("id",l.id(),"key",l.key(),"note",l.note(),"roomCount",repository.rooms().stream().filter(r->r.ownerId().equals(l.id())).count())).toList(); }
    @PostMapping("/licenses") public Map<String,String> addLicense(@RequestBody Map<String,String> body,HttpServletRequest request) { access.root(token(request));return Map.of("id",repository.addLicense(body.get("key"),body.get("note")).id()); }
    @PatchMapping("/licenses/{id}/note") public Map<String,String> updateLicenseNote(@PathVariable String id,@RequestBody Map<String,String> body,HttpServletRequest request) { access.root(token(request));repository.updateLicenseNote(id,body.get("note"));return Map.of("message","备注已更新"); }
    @PutMapping("/licenses/{id}") public Map<String,String> updateLicense(@PathVariable String id,@RequestBody Map<String,String> body,HttpServletRequest request) { access.root(token(request));repository.replaceLicense(id,body.get("key"));access.revokeLicense(id);lifecycle.revokeInvalidManagers();return Map.of("message","许可已更新"); }
    @DeleteMapping("/licenses/{id}") public Map<String,String> deleteLicense(@PathVariable String id,HttpServletRequest request) { access.root(token(request));for(String room:repository.removeLicense(id)) lifecycle.deleted(room);access.revokeLicense(id);lifecycle.revokeInvalidManagers();return Map.of("message","许可及所属房间已删除"); }
    private Map<String,Object> roomInfo(RoomRepository.Room r,boolean manager) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("id",r.id());result.put("name",r.name());
        if(manager) {result.put("ownerId",r.ownerId());result.put("pairingCode",r.pairingCode());result.put("nextUpdateAt",repository.nextPairingUpdateAt(System.currentTimeMillis()));result.put("serverTime",System.currentTimeMillis());result.put("pairingIntervalMinutes",repository.pairingIntervalMinutes());result.put("pairingOpen",r.pairingOpen());}
        return result;
    }
}
