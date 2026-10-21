/**
 * UNM 动态音源匹配规则脚本
 * 运行环境: QuickJS (ES2020)
 * 宿主桥接 API:
 *   nativeBridge.httpGet(url, ua, headersJson)
 *   nativeBridge.httpPost(url, body, headersJson, ua)
 *   nativeBridge.md5(text)
 */

function unmMatch(id, name, artist, duration) {
    var keyword = encodeURIComponent(name + ' ' + artist);

    // 1. 波点音乐源
    try {
        var bodianRes = matchBodian(keyword, duration);
        if (bodianRes && isValidAudio(bodianRes.url)) {
            return bodianRes;
        }
    } catch (e) {}

    // 2. 酷我音乐源
    try {
        var kuwoRes = matchKuwo(keyword, duration);
        if (kuwoRes && isValidAudio(kuwoRes.url)) {
            return kuwoRes;
        }
    } catch (e) {}

    // 3. 酷狗音乐源
    try {
        var kugouRes = matchKugou(keyword, duration);
        if (kugouRes && isValidAudio(kugouRes.url)) {
            return kugouRes;
        }
    } catch (e) {}

    return null;
}

function isValidAudio(url) {
    if (!url || typeof url !== 'string' || url.indexOf('http') !== 0) return false;
    // 过滤下架提示音频标识
    if (url.indexOf('588957081') >= 0 || url.indexOf('563182854') >= 0) return false;
    return true;
}

// ================= 波点音乐源 =================
function matchBodian(keyword, duration) {
    var searchUrl = 'http://search.kuwo.cn/r.s?correct=1&vipver=1&stype=comprehensive&encoding=utf8&rformat=json&mobi=1&show_copyright_off=1&searchapi=6&all=' + keyword;
    var searchRes = nativeBridge.httpGet(searchUrl, 'okhttp/3.10.0');
    if (!searchRes) return null;

    var json = JSON.parse(searchRes);
    var abslist = json.content && json.content[1] && json.content[1].musicpage && json.content[1].musicpage.abslist;
    if (!abslist || abslist.length === 0) return null;

    var rid = abslist[0].MUSICRID.replace('MUSIC_', '');

    // 发送免广告激活请求解锁 VIP 权益
    var devId = '10086' + Math.floor(Math.random() * 1000000);
    var adHeaders = JSON.stringify({
        'user-agent': 'Dart/2.19 (dart:io)',
        'plat': 'ar',
        'channel': 'aliopen',
        'devid': devId,
        'ver': '3.9.0',
        'host': 'bd-api.kuwo.cn',
        'qimei36': '1e9970cbcdc20a031dee9f37100017e1840e',
        'content-type': 'application/json; charset=utf-8'
    });
    var adData = JSON.stringify({ type: 5, subType: 5, musicId: 0, adToken: '' });
    nativeBridge.httpPost('http://bd-api.kuwo.cn/api/service/advert/watch?uid=-1&token=&timestamp=1724306124436&sign=15a676d66285117ad714e8c8371691da', adData, adHeaders);

    // 请求波点音频直链并计算签名
    var baseAudioUrl = 'http://bd-api.kuwo.cn/api/play/music/v2/audioUrl?&br=320kmp3&musicId=' + rid;
    var now = Date.now();
    var strWithTime = baseAudioUrl + '&timestamp=' + now;
    var query = strWithTime.substring(strWithTime.indexOf('?') + 1);
    var filteredChars = query.replace(/[^a-zA-Z0-9]/g, '').split('').sort().join('');
    var path = '/api/play/music/v2/audioUrl';
    var sign = nativeBridge.md5('kuwotest' + filteredChars + path);
    var signedUrl = strWithTime + '&sign=' + sign;

    var playHeaders = JSON.stringify({
        'user-agent': 'Dart/2.19 (dart:io)',
        'plat': 'ar',
        'channel': 'aliopen',
        'devid': devId,
        'ver': '3.9.0',
        'host': 'bd-api.kuwo.cn',
        'X-Forwarded-For': '1.0.1.114'
    });

    var playRes = nativeBridge.httpGet(signedUrl, 'Dart/2.19 (dart:io)', playHeaders);
    if (playRes) {
        var playJson = JSON.parse(playRes);
        if (playJson.code === 200 && playJson.data && playJson.data.audioUrl) {
            return {
                url: playJson.data.audioUrl,
                br: 320000,
                source: 'bodian-js',
                matchedTitle: abslist[0].SONGNAME || abslist[0].name || '',
                matchedArtist: abslist[0].ARTIST || abslist[0].artist || '',
                duration: playJson.data.duration || abslist[0].DURATION || 0
            };
        }
    }
    return null;
}

// ================= 酷我音乐源 =================
function matchKuwo(keyword, duration) {
    var searchUrl = 'http://search.kuwo.cn/r.s?correct=1&vipver=1&stype=comprehensive&encoding=utf8&rformat=json&mobi=1&show_copyright_off=1&searchapi=6&all=' + keyword;
    var searchRes = nativeBridge.httpGet(searchUrl, 'okhttp/3.10.0');
    if (!searchRes) return null;

    var json = JSON.parse(searchRes);
    var abslist = json.content && json.content[1] && json.content[1].musicpage && json.content[1].musicpage.abslist;
    if (!abslist || abslist.length === 0) return null;

    var rid = abslist[0].MUSICRID.replace('MUSIC_', '');
    var trackUrl = 'http://antiserver.kuwo.cn/anti.s?type=convert_url&format=mp3&response=url&rid=MUSIC_' + rid;
    var trackRes = nativeBridge.httpGet(trackUrl, 'okhttp/3.10.0');
    if (trackRes && isValidAudio(trackRes)) {
        return {
            url: trackRes,
            br: 320000,
            source: 'kuwo-js',
            matchedTitle: abslist[0].SONGNAME || abslist[0].name || '',
            matchedArtist: abslist[0].ARTIST || abslist[0].artist || '',
            duration: abslist[0].DURATION || 0
        };
    }
    return null;
}

// ================= 酷狗音乐源 =================
function matchKugou(keyword, duration) {
    var searchUrl = 'http://mobilecdn.kugou.com/api/v3/search/song?keyword=' + keyword + '&page=1&pagesize=5';
    var searchRes = nativeBridge.httpGet(searchUrl, 'okhttp/3.10.0');
    if (!searchRes) return null;

    var json = JSON.parse(searchRes);
    var info = json.data && json.data.info;
    if (!info || info.length === 0) return null;

    var song = info[0];
    var hash = song['320hash'] || song['hash'] || '';
    if (!hash) return null;

    var albumId = song.album_id || '';
    var key = nativeBridge.md5(hash + 'kgcloudv2');
    var trackUrl = 'http://trackercdn.kugou.com/i/v2/?key=' + key + '&hash=' + hash + '&appid=1005&pid=2&cmd=25&behavior=play&album_id=' + albumId;
    var trackRes = nativeBridge.httpGet(trackUrl, 'okhttp/3.10.0');
    if (trackRes) {
        var trackJson = JSON.parse(trackRes);
        if (trackJson.url && trackJson.url.length > 0) {
            return {
                url: trackJson.url[0],
                br: 320000,
                source: 'kugou-js',
                matchedTitle: song.songname || (song.filename ? song.filename.split(' - ')[1] : '') || '',
                matchedArtist: song.singername || (song.filename ? song.filename.split(' - ')[0] : '') || '',
                duration: song.duration || 0
            };
        }
    }
    return null;
}

