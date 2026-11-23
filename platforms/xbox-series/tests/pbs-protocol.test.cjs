const test=require('node:test');
const assert=require('node:assert/strict');
const protocol=require('../js/pbs-protocol.js');

test('Retain Android Relay PBS framed video packet byte order',()=>{
    assert.deepEqual(protocol.packetHeader(Uint8Array.from([0,0,4,0,0xff,0xff,0xfe,0x00]),
        protocol.MAX_VIDEO_PACKET),{length:1024,pts90k:4294966784});
    assert.throws(()=>protocol.packetHeader([0,0,0,0,0,0,0,0],protocol.MAX_VIDEO_PACKET));
    assert.throws(()=>protocol.packetHeader([1,0,0,1,0,0,0,0],protocol.MAX_VIDEO_PACKET));
});
test('PS5 PBS configuration readiness only on compatible valid H264 capture',()=>{
    const ready={platform:'PS5',width:1920,height:1080,fps:60,codec:'avc1.640028',sps:'6764',pps:'68aa',resolutions:[720,1080,720,5000]};
    assert.equal(protocol.parseConfig(ready).ready,true);
    assert.deepEqual(protocol.parseConfig(ready).resolutions,[720,1080,720]);
    assert.equal(protocol.parseConfig({...ready,pps:''}).ready,false);
    assert.equal(protocol.parseConfig({...ready,profile_changing:true}).ready,false);
    assert.equal(protocol.parseConfig({...ready,codec:'h265'}).ready,false);
    assert.throws(()=>protocol.parseConfig({...ready,platform:'PS4'}));
    assert.throws(()=>protocol.parseConfig({...ready,width:12000}));
});
test('PS5 host values are checked before any HTTP request',()=>{
    assert.equal(protocol.validHost('192.0.2.10'),true);
    assert.equal(protocol.validHost('ps5.lan'),true);
    for(const bad of ['','abc /xx','http://192.0.2.10','127.0.0.1:9999','user@ps5','x?y','//ps5'])
        assert.equal(protocol.validHost(bad),false,bad);
});
test('Controller wire fields match Android PbsProtocol.PadState.encode',()=>{
    assert.equal(protocol.encodePad(1,{}),'1,0,128,128,128,128,0,0');
    assert.equal(protocol.encodePad(0xffffffff,{buttons:0x4000,lx:13,ly:19,rx:210,ry:220,l2:150,r2:240}),
        '4294967295,16384,13,19,210,220,150,240');
    assert.equal(protocol.encodePad(5,{buttons:0xffffffff}).startsWith('5,1179647,'),true);
});
