/**
 * Proximity access. Backend owns phone sampling, keyfob events and actions.
 * The page only subscribes while enabled and visible; disabling aborts the
 * subscription and removes every feature timer. ES5 for older car WebViews.
 */
var RemoteAccess = (function () {
    'use strict';

    var API = '/api/remote-access';
    var state = {
        ready: false, enabled: false, saving: false, dirty: false, paused: false,
        config: null, status: null, devices: [], captures: {}, samples: [],
        liveRequest: null, pollTimer: null, ageTimer: null, generation: 0,
        sequence: null, receivedAt: 0, lastSampleTime: null, failures: 0, mutation: 0,
        phones: [], viewAddress: '', modelHandle: null, modelMode: '', modelGeneration: 0,
        cachePreviewTried: false, serverSessionId: null, retiredSessions: [],
        savedConfig: null, advancedDraft: null, artRequest: null, artUrl: null, artGeneration: 0,
        modelPosterValid: false, offRenderTried: false, modelTimer: null,
        bluetoothSaving: false, bluetooth: null, bluetoothReceivedAt: 0, bluetoothTransportStale: false
    };
    var fields = [
        'raDeviceSelect', 'raNearThreshold', 'raAwayThreshold', 'raMedianSeconds',
        'raDwellSeconds', 'raCooldownSeconds', 'raNearWindowSeconds', 'raNearDwellSeconds', 'raLearningMode',
        'raUnlockOnArrival', 'raLockOnDeparture', 'raKeepBluetoothOn',
        'raNotifyPush', 'raNotifyTelegram', 'raAdvancedMode', 'raUnlockTrigger', 'raKeyFobEnabled', 'raKeyFobLockEnabled'
    ];

    function el(id) { return document.getElementById(id); }
    function text(id, value) { var node = el(id); if (node) node.textContent = value; }
    function renderBluetoothStatus() {
        var node = el('raBluetoothStatus');
        if (!node) return;
        var extraAge = state.bluetoothReceivedAt ? Math.max(0, Date.now() - state.bluetoothReceivedAt) : 0;
        var value = window.BYD && BYD.utils && BYD.utils.bluetoothStatus
            ? BYD.utils.bluetoothStatus(state.bluetooth, extraAge, state.bluetoothTransportStale)
            : { state: 'unknown', label: tr('common_unknown', '未知'), detail: tr('bt_waiting_status', '等待车机蓝牙状态') };
        text('raBluetoothState', value.label);
        node.setAttribute('data-state', value.state);
        node.setAttribute('aria-label', tr('bt_aria_fmt', '车载蓝牙：{label}。{detail}', { label: value.label, detail: value.detail }));
        node.title = value.detail;
    }
    function updateBluetoothStatus(radio, transportStale) {
        if (!transportStale) {
            state.bluetooth = radio || null;
            state.bluetoothReceivedAt = Date.now();
        }
        state.bluetoothTransportStale = !!transportStale;
        renderBluetoothStatus();
    }
    function copy(input) {
        var output = {}, key;
        for (key in input) if (Object.prototype.hasOwnProperty.call(input, key)) output[key] = input[key];
        return output;
    }
    function finite(value) { return typeof value === 'number' && isFinite(value); }
    function supports(source) {
        return !state.status || !state.status.capabilities || state.status.capabilities[source] !== false;
    }
    function nearWindowValue(config) {
        if (finite(config.nearWindowMs)) return config.nearWindowMs;
        return config.advancedMode && finite(config.windowMs) && config.windowMs !== 15000 ?
            config.windowMs : 4000;
    }
    function nearDwellValue(config) {
        if (finite(config.nearDwellMs)) return config.nearDwellMs;
        return config.advancedMode && finite(config.dwellMs) && config.dwellMs !== 4000 ?
            config.dwellMs : 2000;
    }
    function signalText(value) {
        if (!finite(value)) return '—';
        return String(Math.round(value * 10) / 10).replace('-', '−');
    }
    function tr(key, fallback, vars) {
        var value = window.BYD && BYD.i18n ? BYD.i18n.t('remote_access.' + key, vars) : null;
        if (!value || value === 'remote_access.' + key) {
            value = fallback;
            if (vars && typeof value === 'string') {
                for (var k in vars) if (Object.prototype.hasOwnProperty.call(vars, k)) value = value.split('{' + k + '}').join(String(vars[k]));
            }
        }
        return value;
    }
    function error(message) {
        text('raError', message || '');
        el('raError').hidden = !message;
    }
    function toast(message) {
        if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(message, 'success');
    }
    function visible() { return !document.hidden && !state.paused && !window.OverdriveNativePaused; }
    function isLive() { return state.enabled && visible(); }
    function redirectAuth() {
        window.location.href = '/login.html?redirect=' +
            encodeURIComponent(window.location.pathname + window.location.search);
    }
    function api(path, body) {
        var options = { cache: 'no-store', credentials: 'same-origin' };
        if (body) {
            options.method = 'POST';
            options.headers = { 'Content-Type': 'application/json' };
            options.body = JSON.stringify(body);
        }
        return fetch(API + path, options).then(function (response) {
            if (response.status === 401) { redirectAuth(); throw new Error('Session expired'); }
            return response.json().then(function (data) {
                if (!response.ok || data.success === false) throw new Error(data.error || 'Request failed (' + response.status + ')');
                return data;
            });
        });
    }
    function setBusy(busy) {
        state.saving = busy;
        el('raMasterToggle').disabled = !state.ready || busy;
        el('raSaveBtn').disabled = !state.ready || busy;
        el('raRefreshBtn').disabled = busy;
        fields.forEach(function (id) { el(id).disabled = !state.ready || busy; });
        el('raFastUnlockDefaults').disabled = !state.ready || busy;
        updateUnlockTrigger();
        el('raAddPhoneSelect').disabled = !state.ready || busy;
        el('raAddPhoneBtn').disabled = !state.ready || busy || state.phones.length >= 8 || !el('raAddPhoneSelect').value;
        renderRoster();
        if (state.ready) renderSignal();
    }

    function applyConfig(config) {
        el('raMedianSeconds').value = (config.windowMs || 15000) / 1000;
        el('raDwellSeconds').value = (config.dwellMs || 4000) / 1000;
        el('raNearWindowSeconds').value = nearWindowValue(config) / 1000;
        el('raNearDwellSeconds').value = nearDwellValue(config) / 1000;
        el('raCooldownSeconds').value = (config.cooldownMs || 30000) / 1000;
        el('raLearningMode').checked = config.dryRun !== false;
        el('raUnlockOnArrival').checked = !!config.unlockEnabled;
        el('raUnlockTrigger').value = config.unlockTrigger === 'near' ? 'near' : 'away_to_near';
        el('raKeyFobEnabled').checked = config.keyFobEnabled === true;
        el('raKeyFobLockEnabled').checked = config.keyFobEnabled === true && config.keyFobLockEnabled === true;
        el('raLockOnDeparture').checked = !!config.lockEnabled;
        el('raNotifyPush').checked = !!config.notifyPush;
        el('raNotifyTelegram').checked = !!config.notifyTelegram;
        el('raAdvancedMode').checked = !!config.advancedMode;
        updateAdvancedView();
        renderDevices(state.viewAddress || config.deviceAddress || '');
        loadProfileThresholds();
        updateRulesSummary();
    }

    function renderDevices(selected) {
        var select = el('raDeviceSelect');
        var wanted = selected !== undefined ? selected : state.viewAddress;
        while (select.firstChild) select.removeChild(select.firstChild);
        var placeholder = document.createElement('option');
        placeholder.value = '';
        placeholder.textContent = state.phones.length ? tr('select_approved', '选择已授权手机') : tr('add_approved', '添加授权手机');
        select.appendChild(placeholder);
        var matched = false;
        state.phones.forEach(function (device) {
            var option = document.createElement('option');
            option.value = device.address || '';
            option.textContent = (device.name || tr('approved_phone_fallback', '已授权手机')) + ' · ' + (device.address || '').slice(-5) +
                (device.enabled === false ? ' · ' + tr('status_disabled', '已禁用') : '');
            if (option.value.toUpperCase() === String(wanted).toUpperCase()) { matched = true; wanted = option.value; }
            select.appendChild(option);
        });
        if (!matched) wanted = state.phones.length ? state.phones[0].address : '';
        state.viewAddress = wanted || '';
        select.value = state.viewAddress;
        select.disabled = !state.ready || state.saving;
        var add = el('raAddPhoneSelect'), addWanted = add.value;
        while (add.firstChild) add.removeChild(add.firstChild);
        var option = document.createElement('option'); option.value = ''; option.textContent = tr('select_phone', '选择一部已配对手机'); add.appendChild(option);
        state.devices.forEach(function (device) {
            if (!device.address || profileFor(device.address)) return;
            var item = document.createElement('option'); item.value = device.address;
            item.textContent = (device.name || tr('paired_device_fallback', '已配对设备')) + ' · ' + device.address.slice(-5);
            add.appendChild(item);
        });
        add.value = addWanted;
        add.disabled = !state.ready || state.saving || state.phones.length >= 8;
        el('raAddPhoneBtn').disabled = add.disabled || !add.value;
        renderRoster();
    }
    function refreshDevices() {
        return api('/devices').then(function (result) {
            state.devices = Array.isArray(result.devices) ? result.devices : [];
            renderDevices();
        });
    }
    function selectedDevice() {
        return profileFor(state.viewAddress) || { address: '', name: '' };
    }
    function profileFor(address) {
        for (var i = 0; i < state.phones.length; i++) {
            if (state.phones[i].address.toUpperCase() === String(address).toUpperCase()) return state.phones[i];
        }
        return null;
    }
    function profilesFromConfig(config) {
        var profiles = Array.isArray(config.phones) ? config.phones : config.deviceAddress ? [{
            address: config.deviceAddress, name: config.deviceName, enabled: true,
            nearThreshold: config.nearThreshold, awayThreshold: config.awayThreshold
        }] : [];
        return profiles.slice(0, 8).map(function (profile) {
            return { address: profile.address, name: profile.name || '', enabled: profile.enabled !== false,
                nearThreshold: finite(profile.nearThreshold) ? profile.nearThreshold : -12,
                awayThreshold: finite(profile.awayThreshold) ? profile.awayThreshold : -24 };
        });
    }
    function storeProfileThresholds() {
        var profile = selectedDevice();
        if (!profile.address) return;
        profile.nearThreshold = el('raNearThreshold').value.trim() ? Number(el('raNearThreshold').value) : null;
        profile.awayThreshold = el('raAwayThreshold').value.trim() ? Number(el('raAwayThreshold').value) : null;
    }
    function loadProfileThresholds() {
        var profile = selectedDevice(), config = state.config || {};
        el('raNearThreshold').value = finite(profile.nearThreshold) ? profile.nearThreshold : finite(config.nearThreshold) ? config.nearThreshold : -12;
        el('raAwayThreshold').value = finite(profile.awayThreshold) ? profile.awayThreshold : finite(config.awayThreshold) ? config.awayThreshold : -24;
        renderCaptures();
    }
    function customProfile(source, address) {
        var profiles = source && source.phones || [];
        for (var i = 0; i < profiles.length; i++) if (String(profiles[i].address).toUpperCase() === String(address).toUpperCase()) return profiles[i];
        return null;
    }
    function advancedSnapshot() {
        storeProfileThresholds();
        return {
            phones: state.phones.map(copy),
            windowMs: Number(el('raMedianSeconds').value) * 1000,
            dwellMs: Number(el('raDwellSeconds').value) * 1000,
            nearWindowMs: Number(el('raNearWindowSeconds').value) * 1000,
            nearDwellMs: Number(el('raNearDwellSeconds').value) * 1000,
            staleMs: state.savedConfig && state.savedConfig.staleMs || 5000,
            cooldownMs: Number(el('raCooldownSeconds').value) * 1000
        };
    }
    function updateAdvancedView() {
        var advanced = el('raAdvancedMode').checked;
        el('remoteAccessPage').classList.toggle('ra-advanced-mode', advanced);
        text('raCalibrationDetail', advanced && !(state.config && state.config.advancedMode) ?
            tr('calib_save_adv_first', '采集手机信号前请先保存高级模式。') :
            tr('calib_draft_hint', '采集将填入当前手机的草稿门限。可根据需要微调，随后点击保存规则。'));
    }
    function toggleAdvanced() {
        var advanced = el('raAdvancedMode').checked;
        if (advanced) {
            var custom = state.advancedDraft || state.savedConfig || state.config || {};
            state.phones.forEach(function (profile) {
                var stored = customProfile(custom, profile.address);
                profile.nearThreshold = stored && finite(stored.nearThreshold) ? stored.nearThreshold : -12;
                profile.awayThreshold = stored && finite(stored.awayThreshold) ? stored.awayThreshold : -24;
            });
            el('raMedianSeconds').value = (custom.windowMs || 15000) / 1000;
            el('raDwellSeconds').value = (custom.dwellMs || 4000) / 1000;
            el('raNearWindowSeconds').value = nearWindowValue(custom) / 1000;
            el('raNearDwellSeconds').value = nearDwellValue(custom) / 1000;
            el('raCooldownSeconds').value = (custom.cooldownMs || 30000) / 1000;
        } else {
            state.advancedDraft = advancedSnapshot();
            state.phones.forEach(function (profile) { profile.nearThreshold = -12; profile.awayThreshold = -24; });
            el('raMedianSeconds').value = 15; el('raDwellSeconds').value = 4; el('raCooldownSeconds').value = 30;
            el('raNearWindowSeconds').value = 4; el('raNearDwellSeconds').value = 2;
        }
        loadProfileThresholds(); updateAdvancedView(); markDirty(); renderSignal();
    }
    function useFastUnlockDefaults() {
        if (!state.ready || state.saving || !el('raAdvancedMode').checked) return;
        el('raNearWindowSeconds').value = 4;
        el('raNearDwellSeconds').value = 2;
        markDirty();
    }
    function selectPhone(address) {
        storeProfileThresholds();
        state.viewAddress = address;
        state.samples = []; state.lastSampleTime = null;
        renderDevices(address); loadProfileThresholds(); renderSignal(); drawChart();
    }
    function addPhone() {
        if (state.saving || state.phones.length >= 8) return;
        var address = el('raAddPhoneSelect').value, device = null;
        state.devices.some(function (candidate) { if (candidate.address === address) { device = candidate; return true; } return false; });
        if (!device || profileFor(address)) return;
        storeProfileThresholds();
        state.phones.push({ address: device.address, name: device.name || '', enabled: true, nearThreshold: -12, awayThreshold: -24 });
        state.viewAddress = device.address;
        state.samples = []; state.lastSampleTime = null;
        renderDevices(device.address); loadProfileThresholds(); markDirty(); renderSignal();
    }
    function renderRoster() {
        var roster = el('raPhoneRoster');
        while (roster.firstChild) roster.removeChild(roster.firstChild);
        if (!state.phones.length) {
            var empty = document.createElement('div'); empty.className = 'ra-roster-empty';
            empty.textContent = tr('roster_empty', '请在下方添加已配对手机。在总开关开启前，不会执行任何监听。');
            roster.appendChild(empty); return;
        }
        state.phones.forEach(function (profile) {
            var row = document.createElement('div'); row.className = 'ra-roster-row' + (profile.address === state.viewAddress ? ' is-selected' : '');
            var name = document.createElement('button'); name.type = 'button'; name.className = 'ra-roster-name'; name.disabled = state.saving;
            var fallbackPhoneName = tr('approved_phone_fallback', '已授权手机');
            var title = document.createElement('strong'); title.textContent = profile.name || fallbackPhoneName;
            var signal = signalFor(profile.address);
            var detail = document.createElement('span');
            var age = estimatedAge(signal);
            var stateLabel = String(signal.state || '').toUpperCase() === 'NEAR' ? tr('status_nearby', '靠近') :
                String(signal.state || '').toUpperCase() === 'AWAY' ? tr('status_away', '远离') : tr('common_unknown', '未知');
            detail.textContent = !profile.enabled ? tr('status_disabled', '已禁用') : !state.enabled ? tr('monitoring_off', '监听已关闭') :
                signal.reason === 'save_phone_first' ? tr('reason_save_phone_first', '请先保存以开始监听') :
                !signal.connected ? tr('reason_disconnected', '已断开 / 等待连接') :
                age === null || age > (state.config && state.config.staleMs || 5000) ? tr('reading_expired', '未知 · 信号读数不可用') :
                stateLabel + (el('raAdvancedMode').checked ? ' · ' + signalText(signal.filteredRssi) + ' RSSI' : '');
            name.appendChild(title); name.appendChild(detail); name.addEventListener('click', function () { selectPhone(profile.address); });
            var toggle = document.createElement('label'); toggle.className = 'ra-switch';
            var input = document.createElement('input'); input.type = 'checkbox'; input.checked = profile.enabled; input.disabled = state.saving;
            input.setAttribute('aria-label', tr('enable_phone_fmt', '启用 {name}', { name: profile.name || fallbackPhoneName }));
            var track = document.createElement('span'); track.className = 'ra-switch-track';
            var thumb = document.createElement('span'); thumb.className = 'ra-switch-thumb'; track.appendChild(thumb);
            input.addEventListener('change', function () { profile.enabled = input.checked; markDirty(); renderRoster(); });
            toggle.appendChild(input); toggle.appendChild(track);
            var remove = document.createElement('button'); remove.type = 'button'; remove.className = 'ra-roster-remove'; remove.textContent = '×'; remove.disabled = state.saving;
            remove.setAttribute('aria-label', tr('remove_phone_fmt', '移除 {name}', { name: profile.name || fallbackPhoneName }));
            remove.addEventListener('click', function () {
                storeProfileThresholds();
                state.phones = state.phones.filter(function (item) { return item.address !== profile.address; });
                delete state.captures[profile.address];
                if (state.viewAddress === profile.address) { state.viewAddress = state.phones.length ? state.phones[0].address : ''; state.samples = []; state.lastSampleTime = null; }
                renderDevices(); loadProfileThresholds(); markDirty(); renderSignal();
            });
            row.appendChild(name); row.appendChild(toggle); row.appendChild(remove); roster.appendChild(row);
        });
    }
    function draft() {
        storeProfileThresholds();
        var device = selectedDevice();
        var config = {
            enabled: state.enabled, deviceAddress: device.address, deviceName: device.name || '',
            nearThreshold: Number(el('raNearThreshold').value),
            awayThreshold: Number(el('raAwayThreshold').value),
            windowMs: Number(el('raMedianSeconds').value) * 1000,
            dwellMs: Number(el('raDwellSeconds').value) * 1000,
            nearWindowMs: Number(el('raNearWindowSeconds').value) * 1000,
            nearDwellMs: Number(el('raNearDwellSeconds').value) * 1000,
            staleMs: state.config && state.config.staleMs || 5000,
            cooldownMs: Number(el('raCooldownSeconds').value) * 1000,
            dryRun: el('raLearningMode').checked,
            unlockEnabled: el('raUnlockOnArrival').checked,
            unlockTrigger: el('raUnlockTrigger').value === 'near' ? 'near' : 'away_to_near',
            keyFobEnabled: el('raKeyFobEnabled').checked,
            keyFobLockEnabled: el('raKeyFobEnabled').checked && el('raKeyFobLockEnabled').checked,
            lockEnabled: el('raLockOnDeparture').checked
        };
        config.notifyPush = el('raNotifyPush').checked;
        config.notifyTelegram = el('raNotifyTelegram').checked;
        config.advancedMode = el('raAdvancedMode').checked;
        config.phones = state.phones.map(copy);
        if (!config.advancedMode) {
            // Basic mode runs server defaults while retaining the user's
            // saved custom calibration for the next Advanced-mode session.
            var custom = state.advancedDraft || state.savedConfig || state.config || {};
            config.phones.forEach(function (profile) {
                var stored = customProfile(custom, profile.address);
                profile.nearThreshold = stored && finite(stored.nearThreshold) ? stored.nearThreshold : -12;
                profile.awayThreshold = stored && finite(stored.awayThreshold) ? stored.awayThreshold : -24;
            });
            var selected = customProfile(config, config.deviceAddress);
            config.nearThreshold = selected ? selected.nearThreshold : -12;
            config.awayThreshold = selected ? selected.awayThreshold : -24;
            config.windowMs = custom.windowMs || 15000; config.dwellMs = custom.dwellMs || 4000;
            config.nearWindowMs = nearWindowValue(custom);
            config.nearDwellMs = nearDwellValue(custom);
            config.staleMs = custom.staleMs || 5000; config.cooldownMs = custom.cooldownMs || 30000;
        }
        ['raNearThreshold', 'raAwayThreshold', 'raMedianSeconds', 'raDwellSeconds',
            'raNearWindowSeconds', 'raNearDwellSeconds', 'raCooldownSeconds'].forEach(function (id) {
            if (el(id).value.trim() === '') throw new Error(tr('err_enter_all_values', '请填写所有信号与时间门限参数。'));
        });
        if (config.enabled && !config.keyFobEnabled && !config.phones.some(function (profile) { return profile.enabled; })) throw new Error(tr('err_enable_source_first', '在开启靠近感知前，请至少启用车钥匙感应或添加并启用一部授权手机。'));
        config.phones.forEach(function (profile) {
            if (!finite(profile.nearThreshold) || !finite(profile.awayThreshold) ||
                Math.floor(profile.nearThreshold) !== profile.nearThreshold || Math.floor(profile.awayThreshold) !== profile.awayThreshold ||
                profile.nearThreshold < -127 || profile.nearThreshold > 20 || profile.awayThreshold < -127 || profile.awayThreshold > 20 ||
                profile.nearThreshold <= profile.awayThreshold) throw new Error(tr('err_threshold_order_phone', '请检查手机“{name}”的信号门限：靠近门限必须大于远离门限。', { name: profile.name || tr('approved_phone_fallback', '已授权手机') }));
        });
        if (!finite(config.nearThreshold) || !finite(config.awayThreshold) ||
            Math.floor(config.nearThreshold) !== config.nearThreshold || Math.floor(config.awayThreshold) !== config.awayThreshold ||
            config.nearThreshold < -127 || config.nearThreshold > 20 || config.awayThreshold < -127 || config.awayThreshold > 20) {
            throw new Error(tr('err_threshold_range', '信号门限必须为 -127 至 20 之间的整数。'));
        }
        if (config.nearThreshold <= config.awayThreshold) throw new Error(tr('err_threshold_order', '靠近门限必须大于远离门限。'));
        if (!finite(config.windowMs) || config.windowMs < 4000 || config.windowMs > 60000 ||
            !finite(config.dwellMs) || config.dwellMs < 2000 || config.dwellMs > 30000 ||
            !finite(config.nearWindowMs) || config.nearWindowMs < 4000 || config.nearWindowMs > 60000 ||
            !finite(config.nearDwellMs) || config.nearDwellMs < 2000 || config.nearDwellMs > 30000 ||
            !finite(config.cooldownMs) || config.cooldownMs < 10000 || config.cooldownMs > 300000) {
            throw new Error('Use 4–60 second filter windows, 2–30 second confirmations, and a 10–300 second cooldown.');
        }
        return config;
    }
    function markDirty() {
        storeProfileThresholds();
        state.dirty = true;
        text('raSaveState', tr('unsaved', '有未保存的修改 · 请点击保存规则以应用'));
        updateRulesSummary();
        drawChart();
        renderPhoneMarkers();
        renderKeyFobStatus();
    }
    function updateUnlockTrigger() {
        var near = el('raUnlockTrigger').value === 'near';
        el('raUnlockTrigger').disabled = !state.ready || state.saving || !el('raUnlockOnArrival').checked;
        var fobDraft = el('raKeyFobEnabled').checked;
        var fob = fobDraft && supports('keyFob');
        var fobLock = fob && supports('keyFobLock') && el('raKeyFobLockEnabled').checked;
        // Keep saved preferences intact, but allow unavailable sources to be turned off.
        el('raKeyFobEnabled').disabled = !state.ready || state.saving || !supports('keyFob') && !fobDraft;
        el('raKeyFobLockEnabled').disabled = !state.ready || state.saving || !fobDraft ||
            (!supports('keyFob') || !supports('keyFobLock')) && !el('raKeyFobLockEnabled').checked;
        text('raKeyFobLockDetail', !supports('keyFob') || !supports('keyFobLock') ?
            tr('keyfob_lock_unsupported', '当前车型不支持钥匙远离落锁检测。可关闭已保存的规则。') :
            !fobDraft ? tr('keyfob_lock_requires_detection', '请先在上方启用“车钥匙靠近感知”以使用离车落锁功能。且必须同时开启“离车自动落锁”。') :
            tr('keyfob_lock_desc', '开启离车自动落锁时，需确认钥匙已远离车辆。同时所有已启用的手机也必须处于远离状态。未知信号将阻止落锁。'));
        var phones = state.phones.some(function (phone) { return phone.enabled; });
        var fobHint = near ?
            tr('keyfob_near_hint', '车钥匙：检测到钥匙即解锁；不需要前置远离事件。') :
            tr('keyfob_return_hint', '车钥匙：需先确认远离，再次检测到钥匙靠近时才触发解锁。');
        var detail = near ?
            tr('unlock_near_detail', '当任意一部已启用的授权手机处于稳定的靠近状态时自动解锁。') :
            tr('unlock_return_detail', '当任意一部已启用的授权手机从“远离”重新回到“靠近”范围时自动解锁。');
        text('raUnlockRuleDetail', fob && !phones ? fobHint :
            detail + (fob ? ' ' + tr('keyfob_unlock_detail', '启用车钥匙感应亦可触发自动解锁。') : ''));
        var hint = near ?
            tr('unlock_trigger_near_hint', '检测到稳定的靠近信号即解锁，包含首次连接或断开重连。无需前置离开过程。') :
            tr('unlock_trigger_away_to_near_hint', '先确认人已走远，重新靠近时方触发解锁。首次连接或信号重连不会误解锁（推荐，防误开锁）。');
        text('raUnlockTriggerHint', fob && !phones ? fobHint :
            (fob ? tr('phone_trigger_prefix', '手机：') + ' ' : '') + hint + (fob ? ' ' + fobHint : ''));
        text('raLockRuleDetail', lockRuleDetail(phones, fobLock));
    }
    function lockRuleDetail(phones, fobLock) {
        if (fobLock) return phones ?
            tr('lock_phone_fob_detail', '所有启用的手机必须全部处于远离状态且确认钥匙已远离。周围有信号或状态未知将阻止落锁。执行前会自动校验电源与车门闭合状态。') :
            tr('lock_fob_detail', '确认车钥匙远离后落锁。未知信号将阻止落锁。执行前会自动校验电源与车门闭合状态。');
        return phones ?
            tr('lock_phone_detail', '所有启用的手机必须全部处于远离状态。靠近、未知或断开连接将阻止落锁。注意：蓝牙无法检测车内是否遗留乘客或钥匙。') :
            tr('lock_no_sources', '离车落锁未激活。请启用已授权手机或开启钥匙远离落锁。');
    }
    function toggleKeyFob() {
        if (!el('raKeyFobEnabled').checked) el('raKeyFobLockEnabled').checked = false;
        markDirty();
    }
    function updateRulesSummary() {
        updateUnlockTrigger();
        var learning = el('raLearningMode').checked;
        var unlock = el('raUnlockOnArrival').checked;
        var lock = el('raLockOnDeparture').checked;
        var fobEnabled = el('raKeyFobEnabled').checked && supports('keyFob');
        var fobOnly = fobEnabled && !state.phones.some(function (phone) { return phone.enabled; });
        var label;
        if (learning) label = tr('preview_note', '监控预览模式已开启，车控指令已暂停执行。');
        else if (!unlock && !lock) label = tr('no_actions', '仅监测模式。已禁用所有自动车控动作。');
        else {
            label = unlock ? el('raUnlockTrigger').value === 'near' ?
                tr('unlock_near_note', '只要检测到稳定的靠近信号即可解锁（包括首次连接或重新连接）。执行前会自动校验车辆电源状态。') :
                tr('unlock_return_note', '必须先经历一次明确的离开，随后重新靠近方可触发解锁。单纯系统启动或重连不会误触发。') : '';
            if (unlock && fobEnabled) label += ' ' + (el('raUnlockTrigger').value === 'near' ?
                tr('keyfob_near_hint', '车钥匙：检测到钥匙即解锁；不需要前置远离事件。') :
                tr('keyfob_return_hint', '车钥匙：需先确认远离，再次检测到钥匙靠近时才触发解锁。'));
            if (unlock && fobOnly) label = (el('raUnlockTrigger').value === 'near' ?
                tr('keyfob_near_hint', '车钥匙：检测到钥匙即解锁；不需要前置远离事件。') :
                tr('keyfob_return_hint', '车钥匙：需先确认远离，再次检测到钥匙靠近时才触发解锁。')) + ' Power and closures are checked.';
            if (lock) label += (label ? ' ' : '') + lockRuleDetail(
                state.phones.some(function (phone) { return phone.enabled; }),
                fobEnabled && supports('keyFobLock') && el('raKeyFobLockEnabled').checked);
        }
        text('raRuleStatus', label);
    }

    function reasonText(reason) {
        var reasons = {
            disabled: tr('reason_disabled', '暂无后台监听'), no_device: tr('reason_no_device', '请选择并保存已配对手机'),
            bluetooth_off: tr('reason_bluetooth_off', '车载蓝牙已关闭'), disconnected: tr('reason_disconnected', '手机未连接 · 暂不判定为远离'),
            stale: tr('reason_stale', '信号数据已过期 · 暂停自动化车控'),
            waiting_for_signal: tr('reason_waiting_signal', '等待最新手机信号'), insufficient_samples: tr('reason_collecting', '正在采集有效样本'),
            unavailable: tr('reason_rssi_unavailable', '当前平台不支持蓝牙 RSSI 测距'), settling: tr('reason_settling', '等待信号稳定'),
            startup: tr('reason_startup', '启动观察中 · 暂不执行动作'), unarmed: tr('reason_unarmed', '确认远离后方可激活靠近解锁'),
            radio_unavailable: tr('reason_bluetooth_unavailable', '车载蓝牙不可用'), reader_unavailable: tr('reason_reader_unavailable', '信号采集服务不可用'),
            radio_off: tr('reason_bluetooth_off', '车载蓝牙已关闭'), bluetooth_unavailable: tr('reason_bluetooth_unavailable', '车载蓝牙不可用'),
            waiting_for_phone: tr('reason_waiting_phone', '等待所选手机连接'),
            waiting_for_collector: tr('reason_waiting_collector', '等待车机服务启动监听'),
            collector_stale: tr('reason_collector_stale', '车机监听服务停止更新 · 自动化已解除'),
            paired_devices_unavailable: tr('reason_paired_unavailable', '等待车机上报已配对设备'),
            rssi_unavailable: tr('reason_rssi_fresh_unavailable', '无法获取最新蓝牙信号强度'),
            unsupported_platform: tr('reason_unsupported_platform', '当前平台不支持读取蓝牙信号'),
            vehicle_ineligible: tr('reason_vehicle_ineligible', '未确认车辆完全熄火断电 · 自动化已解除'),
            stable_near: tr('reason_stable_near', '稳定的靠近信号'), stable_away: tr('reason_stable_away', '稳定的远离信号'),
            stabilizing_near: tr('reason_stabilizing_near', '正在确认靠近信号'), stabilizing_away: tr('reason_stabilizing_away', '正在确认远离信号'),
            within_hysteresis: tr('reason_within_hysteresis', '信号处于滞后区间 · 保持当前状态'),
            save_phone_first: tr('reason_save_phone_first', '请先保存此授权手机以开始监听'),
            phone_disabled: tr('reason_phone_disabled', '此授权手机已停用'),
            master_off: tr('reason_master_off', '靠近感知总开关已关闭'),
            shared_bluetooth_off: tr('reason_shared_bt_off', '请开启驻车共享蓝牙以重连手机'),
            vehicle_on: tr('reason_vehicle_on', '手机重连需等待车辆完全熄火断电'),
            power_unknown: tr('reason_power_unknown', '等待确认车辆熄火断电'),
            vehicle_on_only: tr('reason_vehicle_on_only', '“仅车辆启动时”模式下驻车重连不可用'),
            settings_changed: tr('reason_settings_changed', '手机配置已更改 · 正在校验已授权手机'),
            phone_not_trusted: tr('reason_phone_not_trusted', '保存并启用此授权手机后方可重连'),
            phone_not_paired: tr('reason_phone_not_paired', '请先在车机系统蓝牙设置中配对该手机'),
            profile_loading: tr('reason_profile_loading', '正在准备重连手机'),
            profile_timeout: tr('reason_profile_timeout', '蓝牙连接服务响应超时'),
            profile_busy: tr('reason_profile_busy', '另一部手机处于活跃连接 · 保持连接'),
            other_bluetooth_active: tr('reason_other_bt_active', '其他蓝牙设备处于活跃连接 · 保持连接'),
            bluetooth_busy: tr('reason_bt_busy', '蓝牙正忙 · 暂停重连'),
            discovery_active: tr('reason_discovery_active', '正在扫描蓝牙设备 · 暂停重连'),
            pairing_active: tr('reason_pairing_active', '正在进行蓝牙配对 · 暂停重连'),
            pairing_grace: tr('reason_pairing_grace', '蓝牙设置即将完成 · 暂停重连'),
            bluetooth_state_unavailable: tr('reason_bt_state_unavailable', '蓝牙状态不可用 · 暂停重连'),
            priority_disabled: tr('reason_priority_disabled', '蓝牙设置中已关闭手机自动连接'),
            retry_backoff: tr('reason_retry_backoff', '等待重连重试退避时间'),
            phone_turn: tr('reason_phone_turn', '正在等待重连此授权手机'),
            attempt_accepted: tr('reason_attempt_accepted', '已发送重连请求 · 等待手机响应'),
            attempt_rejected: tr('reason_attempt_rejected', '手机拒绝了重连请求'),
            connection_timeout: tr('reason_connection_timeout', '手机未在规定时间内重连 · 即将重试'),
            connection_failed: tr('reason_connection_failed', '手机连接失败 · 稍后重试'),
            profile_unavailable: tr('reason_profile_unavailable', '当前车型不支持蓝牙回连'),
            connected: tr('reason_connected', '手机已恢复连接 · 等待最新信号'),
            stopped: tr('reason_stopped', '手机重连已停止'),
            unexpected_profile: tr('reason_unexpected_profile', '手机重连服务异常不可用')
        };
        return reasons[reason] || String(reason || tr('reason_waiting_fresh', '等待最新读数')).replace(/_/g, ' ');
    }
    function currentSignal() {
        return signalFor(state.viewAddress);
    }
    function signalFor(address) {
        var signals = state.status && state.status.signals || [];
        for (var i = 0; i < signals.length; i++) {
            if (String(signals[i].deviceAddress).toUpperCase() === String(address).toUpperCase()) return signals[i];
        }
        var signal = state.status && state.status.signal || {};
        if (signal.deviceAddress && signal.deviceAddress.toUpperCase() === String(address).toUpperCase()) return signal;
        return { deviceAddress: address, reason: address ? 'save_phone_first' : 'no_device', state: 'UNKNOWN', connected: false };
    }
    function estimatedAge(signal) {
        return finite(signal.ageMs) ? Math.max(0, signal.ageMs + Date.now() - state.receivedAt) : null;
    }
    function keyFobAge(value) {
        return finite(value) && value >= 0 ? value + Math.max(0, Date.now() - state.receivedAt) : null;
    }
    function eventAgeText(age) {
        if (age === null) return '—';
        if (age < 1000) return '<1s ago';
        if (age < 60000) return Math.floor(age / 1000) + 's ago';
        if (age < 3600000) return Math.floor(age / 60000) + 'm ago';
        return Math.floor(age / 3600000) + 'h ago';
    }
    function keyFobView() {
        var fob = state.status && state.status.keyFob || {};
        var age = keyFobAge(fob.lastValidEventAgeMs);
        var configured = state.config && state.config.keyFobEnabled === true;
        var active = supports('keyFob') && state.enabled && configured && fob.active === true && fob.registered === true && fob.tableRegistered === true;
        var fresh = active && fob.fresh === true && age !== null && age <= 5000;
        var value = String(fob.state || 'UNKNOWN').toUpperCase();
        var label, detail, style;
        if (!supports('keyFob')) {
            label = tr('keyfob_unavailable', '不可用'); detail = tr('kf_reason_unsupported', '当前车型不支持车钥匙感应。'); style = 'unavailable';
        } else if (!el('raKeyFobEnabled').checked) {
            label = tr('keyfob_disabled', '已禁用'); detail = tr('keyfob_disabled_detail', '车钥匙靠近检测已禁用。'); style = 'disabled';
        } else if (!configured) {
            label = tr('keyfob_save_first', '请保存以开始检测'); detail = tr('keyfob_save_first_detail', '保存规则后方可应用车钥匙感应功能。'); style = 'waiting';
        } else if (!state.enabled) {
            label = tr('keyfob_monitoring_off', '监听已关闭'); detail = tr('keyfob_master_off', '总开关已关闭，暂停车钥匙监听与联动动作。'); style = 'disabled';
        } else if (!active) {
            var reason = fob.reason || fob.sourceStatus;
            label = reason === 'vehicle_not_off' ? tr('keyfob_waiting_power', '等待车辆熄火断电') :
                reason === 'vehicle_not_locked' && !(state.config && state.config.keyFobLockEnabled === true) ?
                    tr('keyfob_waiting_lock', '等待车辆落锁') :
                reason === 'collector_stale' ? tr('keyfob_stale', '信号已停止更新') :
                reason === 'stopped' || reason === 'revoked' || reason === 'disabled' ? tr('keyfob_stopped', '已停止') :
                reason === 'receiver_unavailable' || reason === 'table_unavailable' ||
                reason === 'registration_failed' || reason === 'cleanup_failed' ||
                reason === 'unsupported_platform' || reason === 'unavailable' ? tr('keyfob_unavailable', '不可用') :
                tr('keyfob_waiting', '等待车辆信号');
            detail = keyFobReasonText(reason); style = label === tr('keyfob_unavailable', '不可用') ||
                reason === 'collector_stale' ? 'unavailable' : 'waiting';
        } else if (fresh && value === 'NEAR') {
            label = tr('keyfob_detected', '已检测到钥匙');
            detail = tr('keyfob_fresh_detection', '最新钥匙靠近事件') + ' · ' + eventAgeText(age); style = 'detected';
        } else if (fresh && value === 'AWAY') {
            label = tr('keyfob_away_event', '钥匙远离事件');
            detail = tr('keyfob_fresh_away', '最新钥匙远离事件') + ' · ' + eventAgeText(age); style = 'listening';
        } else if (age !== null && (fob.lastKnownState === 'NEAR' || fob.lastKnownState === 'AWAY')) {
            label = fob.lastKnownState === 'NEAR' ? tr('keyfob_last_detected', '上次检测到钥匙') : tr('keyfob_last_away', '上次远离事件');
            detail = eventAgeText(age) + ' · ' + tr('keyfob_listening_next', '正在监听下次事件。当前距离未知。'); style = 'listening';
        } else {
            label = tr('keyfob_listening', '正在监听');
            detail = tr('keyfob_waiting_event', '等待新的钥匙靠近事件。'); style = 'listening';
        }
        if (supports('keyFob') && !supports('keyFobLock') && el('raKeyFobEnabled').checked)
            detail += ' ' + tr('keyfob_lock_unsupported', '当前车型不支持钥匙远离落锁检测。可关闭已保存的规则。');
        return { fob: fob, age: age, active: active, fresh: fresh, state: value, label: label, detail: detail, style: style };
    }
    function keyFobReasonText(reason) {
        if (reason === 'vehicle_not_locked' && state.config && state.config.keyFobLockEnabled === true)
            return tr('keyfob_lock_state_waiting', '等待有效的车锁状态。');
        var labels = {
            disabled: tr('kf_reason_disabled', '车钥匙检测已禁用。'), master_off: tr('kf_reason_master_off', '靠近感知已关闭。'),
            no_device: tr('kf_reason_no_device', '等待车载车钥匙接收模块。'),
            waiting_for_collector: tr('kf_reason_waiting_collector', '等待车机服务启动车钥匙监听。'),
            collector_stale: tr('kf_reason_collector_stale', '车机监听服务停止更新，车钥匙联动已暂停。'),
            scope_stale: tr('kf_reason_scope_stale', '车辆电源或车锁状态已过期，车钥匙联动已暂停。'),
            vehicle_ineligible: tr('kf_reason_vehicle_ineligible', '未确认车辆完全熄火断电，车钥匙联动已暂停。'),
            vehicle_on: tr('kf_reason_vehicle_on', '车钥匙联动需确认车辆已完全熄火断电。'),
            vehicle_not_off: tr('kf_reason_vehicle_not_off', '车钥匙检测需等待车辆确认完全熄火断电。'),
            vehicle_not_locked: tr('kf_reason_vehicle_not_locked', '车钥匙检测仅在车辆锁闭且熄火断电时监听。'),
            power_unknown: tr('kf_reason_power_unknown', '等待确认车辆熄火断电。'),
            lock_unknown: tr('kf_reason_lock_unknown', '等待有效的车辆门锁状态。'),
            departure_unknown: tr('kf_reason_departure_unknown', '尚未确认钥匙远离，自动落锁已暂停。'),
            waiting_for_vehicle: tr('kf_reason_waiting_vehicle', '等待车辆上报电源与门锁状态。'),
            stopped: tr('kf_reason_stopped', '车钥匙监听已停止。'),
            revoked: tr('kf_reason_revoked', '车钥匙配置已变更，正在等待接收模块就绪。'),
            unsupported_platform: tr('kf_reason_unsupported', '当前车型不支持车钥匙感应。'),
            unavailable: tr('kf_reason_unavailable', '车载车钥匙接收模块不可用。'),
            receiver_unavailable: tr('kf_reason_receiver_unavailable', '车载车钥匙接收模块不可用。'),
            table_unavailable: tr('kf_reason_table_unavailable', '车载车钥匙事件流不可用。'),
            registration_failed: tr('kf_reason_reg_failed', '车钥匙监听注册失败。'),
            cleanup_failed: tr('kf_reason_cleanup_failed', '车钥匙接收模块未正常退出，联动动作已暂停。'),
            waiting_for_signal: tr('kf_reason_waiting_signal', '等待最新车钥匙事件。'),
            waiting_for_fresh_event: tr('kf_reason_waiting_fresh', '正在监听最新车钥匙事件。'),
            event_stale: tr('kf_reason_event_stale', '上一条车钥匙事件已过期，等待新事件。'),
            unknown_event: tr('kf_reason_unknown_event', '车载事件未包含车钥匙在场信息。')
        };
        return labels[reason] || tr('keyfob_waiting_source', '等待车身钥匙接收器响应。');
    }
    function renderKeyFobStatus() {
        var panel = el('raKeyFobPanel'), view = keyFobView();
        panel.hidden = supports('keyFob') && el('raKeyFobEnabled').checked !== true;
        panel.setAttribute('data-state', view.style);
        text('raKeyFobState', view.label); text('raKeyFobDetail', view.detail);
        text('raKeyFobMask', finite(view.fob.lastMask) && view.fob.lastMask >= 0 && Math.floor(view.fob.lastMask) === view.fob.lastMask ?
            '0x' + view.fob.lastMask.toString(16).toUpperCase() : '—');
        text('raKeyFobAge', eventAgeText(view.age));
        text('raKeyFobSource', view.active ? 'Vehicle CAN · 0x180' : view.fob.sourceStatus ?
            String(view.fob.sourceStatus).replace(/_/g, ' ') : '—');
        var lockDraft = el('raKeyFobEnabled').checked && el('raKeyFobLockEnabled').checked;
        el('raKeyFobDepartureRow').hidden = !lockDraft;
        text('raKeyFobDeparture', !supports('keyFob') || !supports('keyFobLock') ? tr('unsupported_car', '当前车型不支持') :
            !state.config || state.config.keyFobLockEnabled !== true ?
            tr('keyfob_lock_save_first', '请保存规则以应用') : !state.enabled ?
                tr('keyfob_monitoring_off', '监听已关闭') : view.active && view.fob.departureKnown === true ?
                    tr('keyfob_departure_confirmed', '已确认钥匙远离') :
                    tr('keyfob_departure_waiting', '等待钥匙远离'));
        renderKeyFobMarker(view);
        return view;
    }
    function svgNode(tag, attributes, value) {
        var node = document.createElementNS('http://www.w3.org/2000/svg', tag);
        for (var key in attributes) if (Object.prototype.hasOwnProperty.call(attributes, key)) {
            node.setAttribute(key, attributes[key]);
        }
        if (value !== undefined) node.textContent = value;
        return node;
    }
    function renderPhoneMarkers() {
        var markers = el('raPhoneMarkers'), links = el('raPhoneLinks');
        while (markers.firstChild) markers.removeChild(markers.firstChild);
        while (links.firstChild) links.removeChild(links.firstChild);
        var count = 0;
        // Fixed roster slots keep a phone still when another observation changes.
        var slots = [[208, 44], [326, 71], [376, 142], [326, 204],
            [208, 223], [90, 204], [40, 142], [90, 71]];
        var saved = { phones: profilesFromConfig(state.config || {}) };
        if (supports('bluetooth') && state.enabled && visible()) state.phones.slice(0, 8).forEach(function (phone, index) {
            var approved = customProfile(saved, phone.address);
            if (!phone.enabled || !approved || !approved.enabled) return;
            var signal = signalFor(phone.address), age = estimatedAge(signal);
            if (!signal.connected || String(signal.state).toUpperCase() !== 'NEAR' ||
                !finite(signal.ageMs) || signal.ageMs < 0 || age === null ||
                age > (state.config && state.config.staleMs || 5000) ||
                !finite(signal.timestampMs) || !finite(signal.rawRssi) || !finite(signal.filteredRssi) ||
                (signal.sampleCount || 0) < 3) return;
            var slot = slots[index], name = String(phone.name || signal.deviceName || tr('phone_n_fmt', '手机 {n}', { n: index + 1 }));
            var shortName = name.replace(/\s+/g, ' ').trim();
            if (shortName.length > 9) shortName = shortName.slice(0, 8) + '…';
            var marker = svgNode('g', { 'class': 'ra-phone-marker', role: 'img',
                'aria-label': name + ' · Nearby', transform: 'translate(' + slot[0] + ' ' + slot[1] + ')' });
            marker.appendChild(svgNode('title', {}, name + ' · Nearby'));
            marker.appendChild(svgNode('circle', { 'class': 'ra-phone-halo', r: '22' }));
            marker.appendChild(svgNode('circle', { 'class': 'ra-phone-disc', r: '16' }));
            marker.appendChild(svgNode('rect', { x: '-5.5', y: '-9', width: '11', height: '18',
                rx: '2.5', fill: 'none', stroke: 'currentColor', 'stroke-width': '1.5' }));
            marker.appendChild(svgNode('path', { d: 'M-2-6h4M-1 6h2', fill: 'none',
                stroke: 'currentColor', 'stroke-width': '1.5', 'stroke-linecap': 'round' }));
            marker.appendChild(svgNode('text', { 'class': 'ra-phone-label', x: '0', y: '30',
                'text-anchor': 'middle' }, (index + 1) + ' · ' + shortName));
            var linkX = 208 + (slot[0] - 208) * .45, linkY = 142 + (slot[1] - 142) * .45;
            links.appendChild(svgNode('path', { 'class': 'ra-phone-link', fill: 'none',
                d: 'M' + linkX + ' ' + linkY + 'L' + slot[0] + ' ' + slot[1] }));
            markers.appendChild(marker);
            count++;
        });
        markers.style.display = links.style.display = count ? '' : 'none';
        el('raRadar').setAttribute('data-nearby-phones', String(count));
    }
    function renderKeyFobMarker(view) {
        var markers = el('raKeyFobMarkers'), links = el('raKeyFobLinks');
        while (markers.firstChild) markers.removeChild(markers.firstChild);
        while (links.firstChild) links.removeChild(links.firstChild);
        view = view || keyFobView();
        var shown = visible() && el('raKeyFobEnabled').checked && view.fresh && view.state === 'NEAR';
        if (shown) {
            var label = tr('keyfob_marker', '车钥匙 · 已感应');
            var marker = svgNode('g', { 'class': 'ra-keyfob-marker', role: 'img',
                'aria-label': label, transform: 'translate(150 44)' });
            marker.appendChild(svgNode('title', {}, label));
            marker.appendChild(svgNode('circle', { 'class': 'ra-phone-halo', r: '22' }));
            marker.appendChild(svgNode('circle', { 'class': 'ra-phone-disc', r: '16' }));
            marker.appendChild(svgNode('circle', { cx: '-5', cy: '-4', r: '4.5', fill: 'none',
                stroke: 'currentColor', 'stroke-width': '1.6' }));
            marker.appendChild(svgNode('path', { d: 'M-2-1L8 9M3 4l3-3M6 7l3-3', fill: 'none',
                stroke: 'currentColor', 'stroke-width': '1.6', 'stroke-linecap': 'round',
                'stroke-linejoin': 'round' }));
            marker.appendChild(svgNode('text', { 'class': 'ra-phone-label', x: '0', y: '30',
                'text-anchor': 'middle' }, tr('keyfob_marker_label', '车钥匙')));
            links.appendChild(svgNode('path', { 'class': 'ra-phone-link', fill: 'none',
                d: 'M182 98L150 44' }));
            markers.appendChild(marker);
        }
        markers.style.display = links.style.display = shown ? '' : 'none';
        el('raRadar').setAttribute('data-keyfob-near', shown ? 'true' : 'false');
    }
    function renderSignal() {
        renderBluetoothStatus();
        updateRulesSummary();
        var fob = renderKeyFobStatus();
        var signal = currentSignal();
        var age = estimatedAge(signal);
        var stale = state.enabled && (!supports('bluetooth') || age === null || age > (state.config && state.config.staleMs || 5000));
        var connected = !!signal.connected;
        var reconnect = signal.reconnect || {};
        var reconnectReason = !connected && selectedDevice().enabled && signal.reason !== 'phone_disabled' && reconnect.reason;
        var proximity = !state.enabled ? 'DISABLED' : stale || !connected ? 'UNKNOWN' : String(signal.state || 'UNKNOWN').toUpperCase();
        var label = proximity === 'NEAR' ? tr('status_nearby', '靠近') :
                    proximity === 'AWAY' ? tr('status_away', '远离') :
                    proximity === 'DISABLED' ? tr('status_disabled', '已关闭') : tr('common_unknown', '未知');
        var detail = !state.enabled ? tr('reason_disabled', '暂无后台监听') :
            !connected ? reconnectReason === 'unsupported_platform' ? tr('reason_reconnect_unsupported', '当前平台不支持手机重连') :
                reasonText(reconnectReason || signal.reason || 'disconnected') :
            stale ? reasonText('stale') : reasonText(signal.reason);
        if (state.enabled && connected && !stale && signal.candidate && signal.candidate !== signal.state && signal.candidate !== 'UNKNOWN') {
            var candText = signal.candidate === 'NEAR' ? tr('status_nearby', '靠近') : tr('status_away', '远离');
            detail = tr('confirming_candidate', '正在确认{state} · 稳定 {sec} 秒', { state: candText, sec: Math.floor((signal.stableForMs || 0) / 1000) });
        } else if (state.enabled && connected && !stale && proximity === 'NEAR') {
            detail = state.config && state.config.unlockTrigger === 'near' ? tr('stable_nearby_signal', '稳定的靠近信号') :
                signal.armed ? tr('stable_near_armed', '稳定的靠近信号 · 已进入布防') : tr('stable_near_unarmed', '稳定的靠近信号 · 确认远离后方可激活靠近解锁');
        } else if (state.enabled && connected && !stale && proximity === 'AWAY') {
            detail = signal.armed ? tr('stable_away_armed', '稳定的远离信号 · 等待返回') : tr('stable_away_signal', '稳定的远离信号');
        }
        if (state.enabled && signal.reason === 'vehicle_ineligible') detail = reasonText(signal.reason);
        if (!el('raAdvancedMode').checked && state.enabled && connected && !stale &&
            signal.candidate && signal.candidate !== signal.state && signal.candidate !== 'UNKNOWN') {
            var candState = signal.candidate === 'NEAR' ? tr('status_nearby', '靠近') : tr('status_away', '远离');
            detail = tr('confirming_candidate', '正在确认手机处于{state}状态', { state: candState, sec: '' });
        }
        if (!supports('bluetooth') && state.enabled) {
            label = tr('common.unavailable', '不可用'); detail = tr('phone_proximity_unsupported', '当前车型不支持手机靠近感应。'); proximity = 'UNKNOWN';
        }
        var fobOnly = state.config && state.config.keyFobEnabled === true &&
            !profilesFromConfig(state.config).some(function (phone) { return phone.enabled; });
        if (state.enabled && fobOnly) {
            label = fob.label; detail = fob.detail;
            proximity = fob.fresh && fob.state === 'NEAR' ? 'NEAR' : 'UNKNOWN';
        }
        text('raSignalTitle', !state.enabled ? tr('monitoring_disabled_title', '监听已关闭') : fobOnly ?
            supports('keyFob') ? tr('keyfob_monitoring_title', '正在监听车钥匙信号') : tr('keyfob_unavailable_title', '车钥匙感应不可用') :
            !supports('bluetooth') ? tr('phone_proximity_unavailable', '手机靠近感应不可用') :
            !connected ? tr('waiting_for_phone_title', '等待手机连接') :
            tr('phone_is_state', '手机处于{state}状态', { state: label }));
        text('raProximityState', label);
        text('raProximityDetail', detail);
        el('raRadar').setAttribute('data-state', proximity.toLowerCase());
        text('raRawValue', supports('bluetooth') && state.enabled ? signalText(signal.rawRssi) : '—');
        text('raFilteredValue', supports('bluetooth') && state.enabled ? signalText(signal.filteredRssi) : '—');
        var arrivalWindow = state.config && state.config.nearWindowMs || 4000;
        var departureWindow = state.config && state.config.windowMs || 15000;
        text('raFilterDetail', signal.filterKind === 'arrival' ?
            tr('filter_unlock_detail', '解锁 · 近 3 次样本 / {sec} 秒窗口', { sec: arrivalWindow / 1000 }) :
            tr('filter_lock_detail', '落锁 · {sec} 秒中位数滤波', { sec: departureWindow / 1000 }));
        text('raArrivalDiagnostics', tr('diag_unlock_fmt', '解锁: {rssi} · {count}/3 次新鲜靠近读数 · 稳定 {sec} 秒', {
            rssi: (state.enabled ? signalText(signal.arrivalFilteredRssi) : '—'),
            count: (state.enabled ? signal.nearSampleCount || 0 : 0),
            sec: Math.floor((state.enabled ? signal.nearStableForMs || 0 : 0) / 1000)
        }));
        text('raDepartureDiagnostics', tr('diag_lock_fmt', '落锁: {rssi} · {sec} 秒中位数滤波', {
            rssi: (state.enabled ? signalText(signal.departureFilteredRssi) : '—'),
            sec: departureWindow / 1000
        }));
        text('raAgeValue', !state.enabled || age === null ? '—' : age < 1000 ? '<1s' : Math.floor(age / 1000) + 's');
        text('raFreshDetail', !state.enabled ? tr('no_reading', '暂无读数') : stale ? tr('reading_expired', '已过期 / 不可用') : tr('fresh_samples_fmt', '最新 · {count} 个样本', { count: signal.sampleCount || 0 }));
        text('raConnectionState', state.viewAddress ? connected ? tr('connected_state', '已连接') : tr('disconnected_state', '未连接') : tr('not_selected', '未选择'));
        el('raConnectionDot').className = 'ra-status-dot' + (connected ? ' connected' : '');
        text('raDeviceAddress', state.viewAddress || '—');
        text('raArmedState', state.config && state.config.unlockTrigger === 'near' ?
            tr('departure_not_required', '无需前置远离事件') :
            state.enabled && signal.armed && !stale && connected ? tr('armed_for_return', '已就绪（等待返回）') : tr('disarmed', '未布防'));
        text('raModeLabel', state.config && state.config.dryRun === false ? tr('mode_live', '实际控制') : tr('mode_learning', '校准学习'));
        renderPhoneMarkers();
        var captureReady = supports('bluetooth') && state.enabled && connected && !stale && selectedDevice().enabled &&
            el('raAdvancedMode').checked && state.config && state.config.advancedMode &&
            (signal.sampleCount || 0) >= 3 && !state.saving;
        el('raCaptureNear').disabled = !captureReady;
        el('raCaptureAway').disabled = !captureReady;
        var runtime = state.status && state.status.runtime || {};
        text('raMasterDetail', !state.enabled ?
            runtime.cleanupPending ? tr('master_cleanup_pending', '设置已关闭 · 正在等待车机采集服务退出。') :
            tr('master_disabled_desc', '靠近联动动作与信号采样已停止。驻车共享蓝牙设置不受影响。') :
            runtime.active || fob.active ? supports('keyFob') && state.config && state.config.keyFobEnabled === true ?
                fobOnly ? tr('master_monitoring_fob', '正在监听车钥匙事件。自动化动作需车辆完全熄火断电。') :
                tr('master_monitoring_all', '正在监听授权手机与车钥匙事件。自动化动作需车辆完全熄火断电。') :
                tr('master_monitoring_phones', '正在监听已授权手机。自动化动作需车辆完全熄火断电。') :
            fobOnly ? tr('master_enabled_prefix', '已启用 · ') + fob.detail :
            tr('master_enabled_prefix', '已启用 · ') + reasonText(runtime.reason || signal.reason));
        text('raRadioNote', !supports('bluetoothReconnect') ?
            tr('bt_reconnect_unsupported', '当前车型不支持手机自动回连。驻车共享蓝牙功能仍可使用。') :
            state.config && state.config.keepBluetoothOn === true ?
            state.enabled ?
                tr('bt_proximity_reconnect_note', '车辆驻车熄火时，靠近感知将在有限次数内尝试回连已授权配对手机，并维持现有连接。信号强度仅作相对判定，非绝对物理距离。') :
                tr('bt_shared_on_note', '驻车时保持蓝牙开启。靠近感知关闭时不会主动回连手机。请先在车机系统蓝牙中完成配对。') :
            tr('bt_shared_off_note', '驻车共享蓝牙已关闭。驻车回连需同时开启该设置与靠近感知。请先在车机蓝牙设置中配对手机。'));
        renderRoster();
    }
    function setStreamLabel(label, live) {
        text('raStreamLabel', label);
        el('raStreamState').className = 'ra-stream-state' + (live ? ' is-live' : '');
        el('remoteAccessPage').classList.toggle('ra-live', !!live && isLive());
    }
    function appendSample(signal) {
        if (!supports('bluetooth') || !state.enabled || !finite(signal.timestampMs) || signal.timestampMs === state.lastSampleTime ||
            !finite(signal.rawRssi) || !signal.connected ||
            signal.deviceAddress && signal.deviceAddress.toUpperCase() !== state.viewAddress.toUpperCase()) return;
        state.lastSampleTime = signal.timestampMs;
        state.samples.push({ time: signal.timestampMs, raw: signal.rawRssi, filtered: signal.filteredRssi });
        var cutoff = signal.timestampMs - 90000;
        state.samples = state.samples.filter(function (sample) { return sample.time >= cutoff; }).slice(-180);
    }
    function linePath(samples, key, x, y) {
        var path = '', connected = false;
        samples.forEach(function (sample) {
            if (!finite(sample[key])) { connected = false; return; }
            path += (connected ? 'L' : 'M') + x(sample.time).toFixed(2) + ' ' + y(sample[key]).toFixed(2);
            connected = true;
        });
        return path;
    }
    function drawChart() {
        var samples = state.samples, near = Number(el('raNearThreshold').value), away = Number(el('raAwayThreshold').value);
        if (!finite(near)) near = -12;
        if (!finite(away)) away = -24;
        var min = Math.min(-45, away - 10), max = Math.max(5, near + 10);
        samples.forEach(function (sample) { min = Math.min(min, sample.raw - 5); max = Math.max(max, sample.raw + 5); });
        min = Math.floor(min / 5) * 5; max = Math.ceil(max / 5) * 5;
        var end = samples.length ? samples[samples.length - 1].time : Date.now(), start = end - 90000;
        function x(time) { return 48 + Math.max(0, Math.min(1, (time - start) / 90000)) * 838; }
        function y(value) { return 164 - Math.max(0, Math.min(1, (value - min) / (max - min))) * 148; }
        var filtered = linePath(samples, 'filtered', x, y);
        el('raChartRaw').setAttribute('d', linePath(samples, 'raw', x, y));
        el('raChartFiltered').setAttribute('d', filtered);
        var valid = samples.filter(function (sample) { return finite(sample.filtered); });
        el('raChartFill').setAttribute('d', filtered && valid.length ?
            filtered + 'L' + x(valid[valid.length - 1].time).toFixed(2) + ' 164L' + x(valid[0].time).toFixed(2) + ' 164Z' : '');
        el('raChartNear').setAttribute('d', 'M48 ' + y(near) + 'H886');
        el('raChartAway').setAttribute('d', 'M48 ' + y(away) + 'H886');
        el('raNearLabel').setAttribute('y', Math.max(16, y(near) - 5));
        el('raAwayLabel').setAttribute('y', Math.min(172, y(away) + 13));
        text('raNearLabel', tr('label_near_thresh', '靠近阈值 ') + signalText(near));
        text('raAwayLabel', tr('label_away_thresh', '远离阈值 ') + signalText(away));
        text('raChartMax', signalText(max)); text('raChartMin', signalText(min));
        el('raChartEmpty').hidden = samples.length > 0;
        text('raChartEmpty', state.enabled ? tr('chart_waiting_readings', '等待所选手机上报最新读数…') : tr('chart_enable_prompt', '开启靠近感知后即可查看实时信号曲线。'));
        text('raChartStatus', state.enabled ? tr('chart_readings_count', '已显示 {count} 条读数', { count: samples.length }) : tr('chart_monitoring_off', '监听已关闭'));
    }
    function eventLabel(event) {
        if (event.message) return event.message;
        var labels = {
            arrival: tr('event_arrival', '手机靠近到达'),
            departure: tr('event_departure', '手机远离离开'),
            dry_run: tr('event_dry_run', '学习观察记录'),
            accepted: tr('event_accepted', '指令已发送 · 等待确认'),
            confirmed: tr('event_confirmed', '车辆状态已确认'),
            unconfirmed: tr('event_unconfirmed', '指令已发送 · 状态未确认'),
            blocked: tr('event_blocked', '动作被拦截'),
            failed: tr('event_failed', '动作执行失败')
        };
        return labels[event.type] || String(event.type || tr('event_activity', '动态')).replace(/_/g, ' ');
    }
    function renderEvents(events) {
        var list = el('raEventList');
        while (list.firstChild) list.removeChild(list.firstChild);
        events = Array.isArray(events) ? events.slice(0, 40) : [];
        text('raEventCount', tr('events_count_fmt', '{count} 条记录', { count: events.length }));
        if (!events.length) {
            var empty = document.createElement('div'); empty.className = 'ra-history-empty';
            empty.textContent = tr('history_empty', '感应状态变化与车控动作记录将在此处显示。');
            list.appendChild(empty); return;
        }
        events.forEach(function (event) {
            var row = document.createElement('div'); row.className = 'ra-event';
            var mark = document.createElement('span');
            var kind = String(event.result || event.type || '').toLowerCase();
            mark.className = 'ra-event-mark ' + (kind === 'confirmed' ? 'confirmed' :
                kind === 'blocked' || kind === 'failed' ? kind : kind === 'arrival' || kind === 'departure' || kind === 'dry_run' ? 'detected' : '');
            var content = document.createElement('div'); content.className = 'ra-event-content';
            var title = document.createElement('strong'); title.textContent = eventLabel(event);
            content.appendChild(title);
            if (event.action || event.result) {
                var detail = document.createElement('span');
                detail.textContent = [event.action, event.result].filter(Boolean).join(' · ').replace(/_/g, ' ');
                content.appendChild(detail);
            }
            var time = document.createElement('time');
            if (finite(event.timestampMs) && Math.abs(event.timestampMs) <= 8640000000000000) {
                var date = new Date(event.timestampMs);
                time.dateTime = date.toISOString();
                time.textContent = date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
                time.title = date.toLocaleString();
            } else time.textContent = '—';
            row.appendChild(mark); row.appendChild(content); row.appendChild(time); list.appendChild(row);
        });
    }
    function renderLock(lastAction) {
        if (!lastAction || !lastAction.status) { text('raLockState', tr('common_unknown', '未知')); return; }
        var action = lastAction.action === 'lock' || lastAction.action === 'lock_car' ? tr('action_lock', '落锁') : tr('action_unlock', '解锁');
        var status = lastAction.status;
        var label = status === 'confirmed' ? action + tr('lock_st_confirmed', ' 已确认') :
            status === 'accepted' ? action + tr('lock_st_sent_readback', ' 指令已发 · 等待回读') :
            status === 'unconfirmed' ? action + tr('lock_st_unconfirmed', ' 指令已发 · 未确认') :
            status === 'dry_run' ? action + tr('lock_st_dry_run', ' 动作触发 · 仅观察') :
            status === 'blocked' ? action + tr('lock_st_blocked', ' 动作被拦截') :
            status === 'failed' ? action + tr('lock_st_failed', ' 动作失败') : tr('common_unknown', '未知');
        text('raLockState', label);
        el('raLockState').title = [lastAction.reason, lastAction.confirmationScope].filter(Boolean).join(' · ');
    }
    function acceptStatus(data, forceConfig) {
        if (!data || !data.config) throw new Error('Vehicle returned an incomplete proximity status.');
        if (typeof data.sessionId === 'string' && data.sessionId) {
            if (state.retiredSessions.indexOf(data.sessionId) !== -1) return;
            if (state.serverSessionId && state.serverSessionId !== data.sessionId) {
                state.retiredSessions.push(state.serverSessionId);
                state.retiredSessions = state.retiredSessions.slice(-8);
                state.sequence = null; state.samples = []; state.lastSampleTime = null;
                state.failures = 0;
                stopLive();
            }
            state.serverSessionId = data.sessionId;
        }
        // A pre-save status read can arrive after the newer config response.
        // Never let that older snapshot turn monitoring back on.
        if (finite(data.sequence) && finite(state.sequence) && data.sequence < state.sequence) return;
        var wasEnabled = state.enabled;
        state.status = data; state.config = copy(data.config); state.savedConfig = data.savedConfig || data.config; state.receivedAt = Date.now();
        if (Object.prototype.hasOwnProperty.call(data, 'bluetooth')) updateBluetoothStatus(data.bluetooth, false);
        state.sequence = data.sequence; state.enabled = !!data.config.enabled; state.ready = true;
        // This preference saves independently of proximity-rule drafts and is
        // shared with Surveillance, Automations and key bindings.
        if (!state.bluetoothSaving) el('raKeepBluetoothOn').checked = data.config.keepBluetoothOn === true;
        if (wasEnabled && !state.enabled && !state.modelPosterValid) {
            state.cachePreviewTried = false; state.offRenderTried = false;
        }
        if (!state.enabled) {
            state.samples = []; state.lastSampleTime = null;
        }
        if (Array.isArray(data.devices) && data.devices.length) state.devices = data.devices;
        if (forceConfig || !state.dirty) {
            var oldView = state.viewAddress;
            state.phones = profilesFromConfig(data.config);
            state.viewAddress = profileFor(oldView) ? oldView : data.config.deviceAddress || (state.phones[0] && state.phones[0].address) || '';
        }
        if (forceConfig || !state.dirty) applyConfig(data.config);
        el('raMasterToggle').checked = state.enabled;
        el('raMasterToggle').disabled = state.saving;
        el('raSaveBtn').disabled = state.saving;
        fields.forEach(function (id) { el(id).disabled = state.saving; });
        el('raFastUnlockDefaults').disabled = state.saving;
        updateUnlockTrigger();
        el('remoteAccessPage').classList.toggle('ra-enabled', state.enabled);
        text('raMasterBadge', state.enabled ? tr('status_enabled', '已开启') : data.runtime && data.runtime.cleanupPending ? tr('status_cleanup', '正在退出…') : tr('status_disabled', '已关闭'));
        el('raMasterBadge').className = 'ra-badge' + (state.enabled ? ' is-on' : '');
        appendSample(currentSignal());
        renderSignal(); drawChart(); renderEvents(data.events); renderLock(data.lastAction);
        if (!state.enabled) stopLive();
        else if (visible()) startLive();
        syncVehiclePreview();
    }

    function captureVehiclePoster(canvas) {
        canvas = canvas || el('raVehicleCanvas');
        if (!canvas || !canvas.toDataURL) return;
        try {
            var image = canvas.toDataURL('image/webp', .88);
            if (image && image !== 'data:,') {
                el('raVehiclePoster').src = image;
                el('raVehiclePoster').hidden = false;
                el('raVehiclePlaceholder').hidden = true;
                state.modelPosterValid = true;
            }
        } catch (ignored) { /* Keep the prior valid selected-model poster. */ }
    }
    function abortVehicleArt() {
        state.artGeneration++;
        if (state.artRequest) { var request = state.artRequest; state.artRequest = null; request.abort(); }
    }
    function loadStaticVehicleArt() {
        if (!visible() || state.enabled || state.artRequest || el('raVehiclePoster').src) return;
        var generation = ++state.artGeneration;
        function request(path, type, done) {
            if (generation !== state.artGeneration || !visible() || state.enabled) return;
            var xhr = new XMLHttpRequest(); state.artRequest = xhr;
            xhr.open('GET', path, true); xhr.withCredentials = true; xhr.timeout = 6000;
            if (type === 'blob') xhr.responseType = 'blob';
            var headers = window.BYDAuth ? BYDAuth.getAuthHeaders() : {};
            for (var name in headers) if (Object.prototype.hasOwnProperty.call(headers, name)) xhr.setRequestHeader(name, headers[name]);
            xhr.onload = function () {
                if (state.artRequest === xhr) state.artRequest = null;
                if (generation !== state.artGeneration || !visible() || state.enabled || xhr.status < 200 || xhr.status >= 300) return;
                try { done(type === 'blob' ? xhr.response : JSON.parse(xhr.responseText)); } catch (ignored) {}
            };
            function finish() { if (state.artRequest === xhr) state.artRequest = null; }
            xhr.onerror = finish; xhr.ontimeout = finish; xhr.onabort = finish; xhr.send();
        }
        // Same one-shot selected-vehicle artwork as Vehicle Control's lite
        // hero. No WebGL, polling, Bluetooth read, or feature keepalive.
        request('/api/models/selected', 'json', function (selection) {
            request('/api/models/art?id=' + encodeURIComponent(selection.modelId || ''), 'blob', function (blob) {
                if (!blob || !window.URL || !URL.createObjectURL) return;
                if (state.artUrl) URL.revokeObjectURL(state.artUrl);
                state.artUrl = URL.createObjectURL(blob);
                el('raVehiclePoster').src = state.artUrl; el('raVehiclePoster').hidden = false;
                el('raVehiclePlaceholder').hidden = true;
            });
        });
    }
    function disposeVehiclePreview() {
        state.modelGeneration++;
        if (state.modelTimer !== null) { clearTimeout(state.modelTimer); state.modelTimer = null; }
        if (state.modelHandle) {
            state.modelHandle.dispose();
            state.modelHandle = null;
        }
        state.modelMode = '';
        var canvas = el('raVehicleCanvas');
        // A disposed WebGL canvas cannot be reused as a sprite/2D canvas.
        // Swap the element while the static poster retains the last render.
        if (canvas && canvas.parentNode) {
            var fresh = canvas.cloneNode(false);
            fresh.hidden = true; fresh.style.opacity = '0';
            canvas.parentNode.replaceChild(fresh, canvas);
        }
        if (state.modelPosterValid || el('raVehiclePoster').src) el('raVehiclePoster').hidden = false;
    }
    function syncVehiclePreview(oneShot) {
        if (!window.OverdriveAppShell || !OverdriveAppShell.mountVehicleCanvas) return;
        if (!visible()) { abortVehicleArt(); disposeVehiclePreview(); return; }
        if (!state.enabled && state.modelPosterValid) {
            if (state.modelHandle) disposeVehiclePreview();
            return;
        }
        if (!state.enabled && state.modelHandle && state.modelMode === 'poster') return;
        var mode = state.enabled ? 'live' : oneShot ? 'poster' : 'cache';
        if (state.enabled) abortVehicleArt();
        if (state.modelHandle && state.modelMode === mode) return;
        if (state.modelHandle) disposeVehiclePreview();
        if (mode === 'cache' && state.cachePreviewTried) return;
        if (mode === 'cache') state.cachePreviewTried = true;
        if (mode === 'poster') state.offRenderTried = true;
        var generation = ++state.modelGeneration;
        var canvas = el('raVehicleCanvas');
        canvas.hidden = false; canvas.style.opacity = '0';
        state.modelMode = mode;
        var handle = OverdriveAppShell.mountVehicleCanvas(canvas, {
            view: 'side', cacheOnly: mode === 'cache',
            onReady: function (result) {
                if (generation !== state.modelGeneration || !visible()) return;
                var rendered = result && result.canvas || el('raVehicleCanvas');
                captureVehiclePoster(rendered);
                rendered.style.opacity = '1';
                if (state.enabled) el('raVehiclePoster').hidden = true;
                el('raVehiclePlaceholder').hidden = true;
                if (!state.enabled) disposeVehiclePreview();
            },
            onUnavailable: function () {
                if (generation !== state.modelGeneration) return;
                el('raVehicleCanvas').hidden = true;
                if (!el('raVehiclePoster').src) {
                    text('raVehiclePlaceholder', 'Loading your selected vehicle…');
                    el('raVehiclePlaceholder').hidden = false;
                }
                disposeVehiclePreview();
                if (mode === 'cache' && !state.offRenderTried && !state.enabled) {
                    syncVehiclePreview(true);
                } else loadStaticVehicleArt();
            }
        });
        if (generation !== state.modelGeneration) {
            if (handle) handle.dispose();
        } else {
            state.modelHandle = handle;
            if (mode === 'poster' && handle) {
                // A finite page illustration render, independent of the
                // disabled proximity collector. Release on first poster or
                // bounded failure; never leave idle WebGL/timers behind.
                state.modelTimer = setTimeout(function () {
                    state.modelTimer = null;
                    if (generation !== state.modelGeneration || state.modelPosterValid) return;
                    disposeVehiclePreview(); loadStaticVehicleArt();
                }, 30000);
            }
        }
    }

    function stopLive() {
        state.generation++;
        if (state.pollTimer !== null) { clearTimeout(state.pollTimer); state.pollTimer = null; }
        if (state.ageTimer !== null) { clearInterval(state.ageTimer); state.ageTimer = null; }
        if (state.liveRequest) { var request = state.liveRequest; state.liveRequest = null; request.abort(); }
        if (!state.enabled || !visible()) disposeVehiclePreview();
        if (!visible()) abortVehicleArt();
        setStreamLabel(state.enabled ? tr('stream_paused', '实时画面已暂停') : tr('monitoring_off', '监听已关闭'), false);
        renderPhoneMarkers();
        renderKeyFobMarker();
    }
    function startLive() {
        if (!isLive() || state.liveRequest || state.pollTimer !== null) return;
        syncVehiclePreview();
        if (state.ageTimer === null) state.ageTimer = setInterval(function () { if (isLive()) renderSignal(); }, 1000);
        poll();
    }
    function schedulePoll(delay, generation) {
        if (!isLive() || generation !== state.generation) return;
        state.pollTimer = setTimeout(function () { state.pollTimer = null; poll(); }, delay);
    }
    function poll() {
        if (!isLive() || state.liveRequest) return;
        var generation = state.generation;
        // XMLHttpRequest provides abort on every supported WebView, including
        // older ones without AbortController. Auth mirrors the fetch helper.
        var request = new XMLHttpRequest();
        state.liveRequest = request;
        request.open('GET', API + '/status' + (state.sequence !== null && state.sequence !== undefined ?
            '?after=' + encodeURIComponent(state.sequence) : ''), true);
        request.timeout = 16000; request.withCredentials = true;
        request.setRequestHeader('Cache-Control', 'no-cache');
        var headers = window.BYDAuth ? BYDAuth.getAuthHeaders() : {};
        for (var name in headers) if (Object.prototype.hasOwnProperty.call(headers, name)) request.setRequestHeader(name, headers[name]);
        var completed = false;
        function finish(success) {
            if (completed) return; completed = true;
            if (state.liveRequest === request) state.liveRequest = null;
            if (generation !== state.generation || !isLive()) return;
            if (success) {
                state.failures = 0; setStreamLabel(tr('stream_live', '实时'), true);
                schedulePoll(250, generation);
            } else {
                state.failures++;
                setStreamLabel(tr('stream_reconnecting', '正在重新连接…'), false);
                renderSignal();
                schedulePoll(Math.min(10000, 1500 * state.failures), generation);
            }
        }
        request.onload = function () {
            if (generation !== state.generation || !isLive()) { finish(false); return; }
            if (request.status === 401) { stopLive(); redirectAuth(); return; }
            if (request.status < 200 || request.status >= 300) { finish(false); return; }
            try {
                var data = JSON.parse(request.responseText);
                if (data.success === false) { finish(false); return; }
                // Clear only after parsing, before acceptStatus may start a
                // new subscription; schedule the next request once below.
                state.liveRequest = null;
                state.pollTimer = -1;
                acceptStatus(data, false);
                if (state.pollTimer === -1) state.pollTimer = null;
                finish(true);
            } catch (ignored) { if (state.pollTimer === -1) state.pollTimer = null; finish(false); }
        };
        request.onerror = function () { finish(false); };
        request.ontimeout = function () { finish(false); };
        request.onabort = function () { finish(false); };
        request.send();
    }
    function refresh() {
        error('');
        var mutation = state.mutation;
        return api('/status').then(function (data) {
            if (mutation === state.mutation) acceptStatus(data, !state.dirty);
        }).catch(function (err) {
            if (mutation !== state.mutation) return;
            error(err.message); setStreamLabel(tr('stream_unavailable', '状态不可用'), false);
        });
    }
    function refreshAll() {
        el('raRefreshBtn').disabled = true;
        return Promise.all([refresh(), refreshDevices().catch(function (err) { error(err.message); })])
            .then(function () { el('raRefreshBtn').disabled = state.saving; });
    }
    function save() {
        if (state.saving) return;
        var config;
        try { config = draft(); } catch (err) { error(err.message); return; }
        error(''); setBusy(true); state.mutation++;
        api('/config', config).then(function (data) {
            state.dirty = false; state.advancedDraft = null; acceptStatus(data, true);
            text('raSaveState', tr('saved_on_vehicle', '设置已保存在车机端。')); toast(tr('rules_saved', '靠近感应规则已保存'));
        }).catch(function (err) { error(err.message); }).then(function () { setBusy(false); });
    }
    function toggleBluetoothKeepAlive() {
        var previous = state.config && state.config.keepBluetoothOn === true;
        if (!state.ready || state.saving) {
            el('raKeepBluetoothOn').checked = !!previous; return;
        }
        var requested = el('raKeepBluetoothOn').checked;
        error(''); state.bluetoothSaving = true; setBusy(true); state.mutation++;
        // Never include this alias in generic Save: a stale page must not
        // reverse a shared preference changed elsewhere.
        api('/config', { keepBluetoothOn: requested }).then(function (data) {
            state.bluetoothSaving = false;
            acceptStatus(data, false);
            // A newer session/status may have superseded this POST response.
            el('raKeepBluetoothOn').checked = state.config.keepBluetoothOn === true;
            toast(state.config.keepBluetoothOn === true ?
                tr('bluetooth_saved_on', '已开启驻车共享蓝牙') :
                tr('bluetooth_saved_off', '已关闭驻车共享蓝牙'));
        }).catch(function (err) {
            state.bluetoothSaving = false;
            el('raKeepBluetoothOn').checked = state.config ? state.config.keepBluetoothOn === true : !!previous;
            error(tr('bluetooth_save_failed', '无法保存共享蓝牙设置') + ': ' + err.message);
        }).then(function () { setBusy(false); });
    }
    function toggleMaster() {
        if (state.saving) return;
        var requested = el('raMasterToggle').checked;
        var device = selectedDevice();
        if (requested && !el('raKeyFobEnabled').checked && !state.phones.some(function (profile) { return profile.enabled; })) {
            el('raMasterToggle').checked = state.enabled;
            error(tr('err_enable_source', '请先开启车钥匙检测或添加并启用一部已授权手机，然后再开启靠近感知。')); return;
        }
        error(''); setBusy(true); state.mutation++;
        if (!requested) {
            stopLive();
            disposeVehiclePreview();
            el('remoteAccessPage').classList.remove('ra-live');
            text('raMasterBadge', tr('stopping', '正在停止…'));
        }
        var changes = { enabled: requested };
        if (requested) {
            var pending;
            try { pending = draft(); } catch (err) { error(err.message); el('raMasterToggle').checked = state.enabled; setBusy(false); return; }
            changes.deviceAddress = device.address; changes.deviceName = device.name || '';
            changes.keyFobEnabled = pending.keyFobEnabled;
            changes.keyFobLockEnabled = pending.keyFobLockEnabled;
            changes.phones = pending.phones;
            changes.nearThreshold = pending.nearThreshold; changes.awayThreshold = pending.awayThreshold;
        }
        api('/config', changes).then(function (data) {
            acceptStatus(data, false);
            var enabled = !!(data.config && data.config.enabled);
            if (enabled !== requested) {
                var failure = '';
                (data.events || []).some(function (event) {
                    if (event.type === 'error' || event.type === 'failed' || event.result === 'failed') {
                        failure = event.message || ''; return true;
                    }
                    return false;
                });
                error(failure || (requested ? tr('err_start_failed', '靠近感知启动失败，仍处于关闭状态。') :
                    tr('err_disable_failed', '靠近感知仍处于开启状态，关闭请求未应用。')));
            } else {
                toast(enabled ? tr('proximity_enabled', '靠近感知已开启') :
                    data.runtime && data.runtime.cleanupPending ? tr('disabled_cleanup_pending', '设置已关闭 · 等待采集服务退出') : tr('proximity_disabled', '靠近感知已关闭'));
            }
        }).catch(function (err) {
            error(tr('err_toggle_failed', '无法{action}靠近感知: {error}', {
                action: requested ? tr('common.enable', '开启') : tr('common.disable', '关闭'),
                error: err.message
            }));
            el('raMasterToggle').checked = state.enabled;
            if (state.enabled && visible()) startLive();
        }).then(function () { setBusy(false); renderSignal(); });
    }
    function calibrate(side) {
        if (!supports('bluetooth') || !state.enabled || state.saving || !el('raAdvancedMode').checked || !(state.config && state.config.advancedMode)) return;
        var button = el(side === 'near' ? 'raCaptureNear' : 'raCaptureAway');
        var mutation = state.mutation;
        var address = state.viewAddress;
        button.disabled = true; error('');
        api('/calibrate', { side: side, deviceAddress: address }).then(function (data) {
            if (mutation !== state.mutation) return;
            if (!finite(data.value)) throw new Error('No valid fresh capture was returned.');
            if (!profileFor(address)) return;
            if (!state.captures[address]) state.captures[address] = {};
            state.captures[address][side] = data;
            profileFor(address)[side === 'near' ? 'nearThreshold' : 'awayThreshold'] = Math.round(data.value);
            if (address === state.viewAddress) loadProfileThresholds();
            text('raCalibrationDetail', (side === 'near' ? tr('status_nearby', '靠近') : tr('status_away', '远离')) + tr('capture_added_draft', ' 标定数据已加入草稿，请保存规则生效。'));
            markDirty();
        }).catch(function (err) { error(err.message); }).then(renderSignal);
    }
    function renderCaptures() {
        var captures = state.captures[state.viewAddress] || {};
        ['near', 'away'].forEach(function (side) {
            var capture = captures[side];
            text(side === 'near' ? 'raNearCaptureValue' : 'raAwayCaptureValue',
                capture ? signalText(capture.value) + tr('samples_count_fmt', ' · {count} 个样本', { count: capture.sampleCount }) : tr('no_saved_capture', '无已保存标定'));
        });
    }
    function pause() {
        if (!state.enabled && !state.modelPosterValid) { state.offRenderTried = false; state.cachePreviewTried = false; }
        state.paused = true; stopLive();
    }
    function resume() {
        state.paused = false;
        if (visible()) refresh();
    }
    function init() {
        state.paused = !!window.OverdriveNativePaused;
        fields.forEach(function (id) {
            if (id === 'raDeviceSelect' || id === 'raAdvancedMode' || id === 'raKeepBluetoothOn' || id === 'raKeyFobEnabled') return;
            el(id).addEventListener('change', markDirty);
            if (el(id).tagName === 'INPUT' && el(id).type === 'number') el(id).addEventListener('input', markDirty);
        });
        el('raDeviceSelect').addEventListener('change', function () { selectPhone(el('raDeviceSelect').value); });
        el('raAdvancedMode').addEventListener('change', toggleAdvanced);
        el('raKeyFobEnabled').addEventListener('change', toggleKeyFob);
        el('raFastUnlockDefaults').addEventListener('click', useFastUnlockDefaults);
        el('raKeepBluetoothOn').addEventListener('change', toggleBluetoothKeepAlive);
        el('raAddPhoneSelect').addEventListener('change', function () { el('raAddPhoneBtn').disabled = state.saving || state.phones.length >= 8 || !el('raAddPhoneSelect').value; });
        el('raAddPhoneBtn').addEventListener('click', addPhone);
        el('raSettingsForm').addEventListener('submit', function (event) { event.preventDefault(); save(); });
        el('raMasterToggle').addEventListener('change', toggleMaster);
        el('raRefreshBtn').addEventListener('click', refreshAll);
        el('raCaptureNear').addEventListener('click', function () { calibrate('near'); });
        el('raCaptureAway').addEventListener('click', function () { calibrate('away'); });
        document.addEventListener('visibilitychange', function () {
            if (document.hidden) stopLive(); else if (!state.paused) refresh();
        });
        window.addEventListener('overdrive:pause', pause);
        window.addEventListener('overdrive:resume', resume);
        window.addEventListener('focus', function () { if (visible() && state.ready && !state.saving) refresh(); });
        window.addEventListener('pagehide', pause);
        window.addEventListener('beforeunload', function () {
            abortVehicleArt();
            if (state.artUrl) URL.revokeObjectURL(state.artUrl);
        });
        window.addEventListener('pageshow', function (event) { if (event.persisted) resume(); });
        document.addEventListener('overdrive:vehicle-changed', function () {
            abortVehicleArt(); disposeVehiclePreview(); state.cachePreviewTried = false;
            state.modelPosterValid = false; state.offRenderTried = false;
            if (state.artUrl) { URL.revokeObjectURL(state.artUrl); state.artUrl = null; }
            el('raVehiclePoster').removeAttribute('src'); el('raVehiclePoster').hidden = true;
            el('raVehiclePlaceholder').hidden = false;
            syncVehiclePreview();
        });
        drawChart(); refreshAll();
    }
    return { init: init, pause: pause, resume: resume, updateBluetoothStatus: updateBluetoothStatus };
}());
