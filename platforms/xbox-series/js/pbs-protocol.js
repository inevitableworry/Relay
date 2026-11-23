/* SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
 * Relay's own PBS wire format, adapted from android/.../jb/PbsProtocol.kt.
 * No other remote-play application code is used.
 */
(function (root) {
    'use strict';
    var MAX_VIDEO_PACKET = 16 * 1024 * 1024;
    var MAX_AUDIO_PACKET = 65536;
    var LEGACY_BUTTON_MASK = 0x0013fffe;
    var NATIVE_BUTTON_MASK = 0x0011ffff;

    function validHost(raw) {
        var host = String(raw || '').trim();
        if (!host || host.length > 253 || /[\/?#@\\\s:]/.test(host)) return false;
        if (!/^[a-z0-9.\-]+$/i.test(host) || host.charAt(0) === '-' || host.charAt(host.length - 1) === '-') return false;
        return true;
    }
    function boundedInteger(value, min, max) {
        return typeof value === 'number' && isFinite(value) && Math.floor(value) === value &&
            value >= min && value <= max;
    }
    function parseConfig(raw) {
        var j = typeof raw === 'string' ? JSON.parse(raw) : raw;
        if (!j || j.platform !== 'PS5') throw new Error('Endpoint is not a Relay PS5 capture service');
        if (!boundedInteger(j.width, 0, 8192) || !boundedInteger(j.height, 0, 4320))
            throw new Error('Invalid video dimensions');
        var width = j.width, height = j.height;
        var fps = boundedInteger(j.fps, 1, 120) ? j.fps : 60;
        var codec = typeof j.codec === 'string' ? j.codec : '';
        var ready = width > 0 && height > 0 && /^avc1\./.test(codec) &&
            typeof j.sps === 'string' && j.sps.length > 0 &&
            typeof j.pps === 'string' && j.pps.length > 0 && j.profile_changing !== true;
        var audio = j.audio || null;
        return { width:width, height:height, fps:fps, codec:codec, ready:ready,
            audioCodec:audio && typeof audio.codec === 'string' ? audio.codec : '',
            resolutions:Array.isArray(j.resolutions) ? j.resolutions.filter(function (n) {
                return n === 720 || n === 1080 || n === 1440 || n === 2160;
            }) : [] };
    }
    function packetHeader(bytes, limit) {
        if (!bytes || bytes.length < 8) throw new Error('PBS packet header is incomplete');
        var length = ((bytes[0] * 0x1000000) + (bytes[1] << 16) + (bytes[2] << 8) + bytes[3]) >>> 0;
        var pts90k = ((bytes[4] * 0x1000000) + (bytes[5] << 16) + (bytes[6] << 8) + bytes[7]) >>> 0;
        if (!length || length > limit) throw new Error('Invalid PBS packet size');
        return { length:length, pts90k:pts90k };
    }
    function encodePad(sequence, pad) {
        var state = pad || {};
        var fields = [sequence >>> 0, (state.buttons || 0) & NATIVE_BUTTON_MASK];
        ['lx','ly','rx','ry'].forEach(function (f) {
            fields.push(boundedInteger(state[f], 0, 255) ? state[f] : 128);
        });
        ['l2','r2'].forEach(function (f) {
            fields.push(boundedInteger(state[f], 0, 255) ? state[f] : 0);
        });
        return fields.join(',');
    }
    var api = { MAX_VIDEO_PACKET:MAX_VIDEO_PACKET, MAX_AUDIO_PACKET:MAX_AUDIO_PACKET,
        LEGACY_BUTTON_MASK:LEGACY_BUTTON_MASK, NATIVE_BUTTON_MASK:NATIVE_BUTTON_MASK,
        validHost:validHost, parseConfig:parseConfig, packetHeader:packetHeader, encodePad:encodePad };
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
    else root.RelayPbs = api;
})(this);
