# 앱에서 저장한 배치를 구글 시트에 자동으로 넣기

앱은 배치 시트를 "읽기"만 할 수 있습니다. 앱에서 저장한 배치번호와 날짜를 시트에도 넣으려면
시트에 아래 스크립트를 한 번만 설치하면 됩니다. 이 스크립트는 **줄을 추가하거나 빈 칸을 채우기만 하고,
줄을 지우지 않습니다.**

## 설치 (PC에서 한 번)

1. 배치 시트를 엽니다.
2. 메뉴에서 **확장 프로그램 → Apps Script**를 누릅니다.
3. 편집기에 있는 내용을 모두 지우고 아래 코드를 붙여 넣은 뒤 저장합니다.
4. 오른쪽 위 **배포 → 새 배포**를 누릅니다.
   - 유형 선택: **웹 앱**
   - 다음 사용자 인증 정보로 실행: **나**
   - 액세스 권한이 있는 사용자: **모든 사용자**
5. **배포**를 누르고 권한을 허용합니다.
6. 표시되는 **웹 앱 URL**(`https://script.google.com/macros/s/.../exec`)을 복사합니다.
7. 앱의 **프린터 설정 → 배치 목록 Google 시트 → 배치 저장용 주소**에 붙여 넣습니다.

주소를 아는 사람은 시트에 배치를 추가할 수 있으니 주소는 다른 사람에게 알리지 마세요.

## 동작

- 앱 인쇄 화면에서 **이 배치를 목록에 저장**을 누르면 폰에 저장되고, 주소가 설정되어 있으면 시트에도 보냅니다.
- 인터넷이 없어서 못 보낸 배치는 다음에 저장하거나 목록을 새로고침할 때 다시 보냅니다.
- 시트에 같은 배치번호가 이미 있으면 새 줄을 만들지 않고 그 줄의 해당 칸만 채웁니다.
- 스크립트는 첫 번째 탭에 씁니다. 머리글에 `Batch`가 들어간 줄을 찾아 열을 정하고,
  머리글이 없으면 1열=배치, 2열=Best Before, 3열=MFG Date로 씁니다.

## 코드

```javascript
function doPost(e) {
  var lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    var d = JSON.parse(e.postData.contents);
    var batch = String(d.batch || '').trim();
    if (!batch) return out({ ok: false, error: 'no batch' });

    var sh = SpreadsheetApp.getActiveSpreadsheet().getSheets()[0];
    var values = sh.getDataRange().getDisplayValues();

    // Find the header row and columns the same way the app does.
    var header = -1, cBatch = 0, cMgo = 1, cUmf = -1, cMfg = 2;
    for (var r = 0; r < Math.min(5, values.length); r++) {
      var h = values[r].map(function (v) { return String(v).trim().toLowerCase(); });
      var b = indexOf(h, isBatch);
      if (b >= 0) {
        header = r; cBatch = b; cUmf = -1; cMgo = -1;
        for (var i = 0; i < h.length; i++) {
          if (!isBb(h[i])) continue;
          if (h[i].indexOf('umf') >= 0) { if (cUmf < 0) cUmf = i; }
          else if (cMgo < 0 || h[i].indexOf('mgo') >= 0) cMgo = i;
        }
        cMfg = indexOf(h, isMfg);
        break;
      }
    }

    // Use the existing row for this batch, otherwise add a new row at the end. Rows are never deleted.
    var row = -1;
    for (var k = header + 1; k < values.length; k++) {
      if (String(values[k][cBatch]).trim().toLowerCase() === batch.toLowerCase()) { row = k + 1; break; }
    }
    if (row < 0) { row = sh.getLastRow() + 1; put(sh, row, cBatch, batch); }
    put(sh, row, cMgo, d.bbMgo);
    put(sh, row, cUmf, d.bbUmf);
    put(sh, row, cMfg, d.mfg);
    return out({ ok: true, row: row });
  } catch (err) {
    return out({ ok: false, error: String(err) });
  } finally {
    lock.releaseLock();
  }
}

// Write as plain text so dates stay exactly as typed in the app.
function put(sh, row, col, value) {
  var v = String(value || '').trim();
  if (col < 0 || !v) return;
  sh.getRange(row, col + 1).setNumberFormat('@').setValue(v);
}

function out(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

function indexOf(list, test) {
  for (var i = 0; i < list.length; i++) if (test(list[i])) return i;
  return -1;
}
function isBatch(h) { return h.indexOf('batch') >= 0 || h.indexOf('배치') >= 0 || h.indexOf('lot') >= 0; }
function isBb(h) {
  return h.indexOf('best') >= 0 || h === 'bb' || h.indexOf('b.b') >= 0 || h.indexOf('bb ') >= 0 ||
    h.indexOf(' bb') >= 0 || h.indexOf('bb(') >= 0 || h.indexOf('exp') >= 0 ||
    h.indexOf('유통') >= 0 || h.indexOf('소비') >= 0;
}
function isMfg(h) {
  return h.indexOf('mfg') >= 0 || h.indexOf('manuf') >= 0 || h.indexOf('제조') >= 0 || h.indexOf('생산') >= 0 ||
    (h.indexOf('prod') >= 0 && h.indexOf('date') >= 0);
}
```
