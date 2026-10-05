(() => {
  const $=(id)=>document.getElementById(id), e=escapeHtml, capture=window.taskCapture;
  let sourceGroup='', opener=null, drafts=[];
  document.body.insertAdjacentHTML('beforeend', `<dialog id="quick-input-dialog" aria-labelledby="quick-input-title">
    <header class="capture-dialog-head"><div><h2 id="quick-input-title">Что записать?</h2><p id="quick-input-context"></p></div><button class="quiet-icon" type="button" data-close-capture aria-label="Закрыть быстрый ввод">${icon('cancel')}</button></header>
    <form id="quick-input-form">
      <div class="capture-input-stack"><textarea id="quick-input-text" aria-label="Рассказ о задачах" placeholder="Завтра позвонить Саше, купить продукты и отправить макет…" maxlength="2000"></textarea><div class="capture-voice-demo" id="quick-recording" aria-hidden="true"><span class="wave" aria-hidden="true"></span><strong>Пример голосовой записи</strong><span>«Завтра позвонить Саше и купить продукты»</span></div></div>
      <div class="capture-file" id="quick-file-preview" hidden><span id="quick-file-name"></span><button class="quiet-icon" type="button" id="quick-file-remove" aria-label="Убрать аудиофайл">${icon('cancel')}</button></div>
      <div class="capture-record-actions" id="quick-record-actions" hidden><button class="action primary" type="button" id="quick-voice-finish">Разобрать пример</button><button class="action" type="button" id="quick-voice-cancel">Отменить запись</button></div>
      <div class="capture-send-actions" id="quick-send-actions"><button class="action" type="button" id="quick-file-button" aria-label="Прикрепить аудиофайл">${icon('paperclip')}<span>Аудиофайл</span></button><button class="action" type="button" id="quick-voice-button">${icon('mic')}Голос</button><button class="action primary" id="quick-send" type="submit" disabled>Записать${icon('arrow')}</button></div>
      <input id="quick-file-input" type="file" accept="audio/*,.ogg,.opus,.mp3,.m4a,.wav,.webm" hidden>
      <button class="capture-file-example" id="quick-file-example" type="button" hidden>Показать пример расшифровки</button>
      <p id="quick-input-error" class="field-error" role="alert" hidden></p>
      <p class="capture-input-note">Новые задачи появляются сразу. Изменения существующих дел проверишь перед применением.</p>
      <small class="capture-demo-note">Деморежим: разбор по примерам. Запись и распознавание аудио не подключены.</small>
    </form>
    <section id="quick-review" hidden><h3>Проверь изменения</h3><div id="quick-review-items"></div><div class="actions"><button class="action primary" id="quick-apply">Применить</button><button class="action" id="quick-back">Вернуться к рассказу</button></div></section>
    <section id="quick-result" hidden><h3>Уже в твоём списке</h3><div id="quick-result-items"></div><div class="actions"><button class="action primary" data-close-capture>Готово</button><button class="action" id="quick-another">Рассказать ещё</button></div></section>
  </dialog>`);
  const dialog=$('quick-input-dialog'), input=$('quick-input-text');
  const wave=$('quick-recording').querySelector('.wave');wave.innerHTML='<i></i>'.repeat(34);waveforms.push({element:wave,bars:[...wave.children]});
  function recording(active) {
    $('quick-recording').setAttribute('aria-hidden',String(!active));$('quick-input-form').classList.toggle('is-recording',active);
    input.disabled=active;$('quick-record-actions').hidden=!active;$('quick-send-actions').hidden=active;paintWaves();
  }
  function clearAudio() {$('quick-file-input').value='';$('quick-file-preview').hidden=true;$('quick-file-example').hidden=true;}
  function update() {$('quick-send').disabled=!input.value.trim();$('quick-input-error').hidden=true;}
  function close() {dialog.close();}
  function showInput() {drafts=[];$('quick-review-items').innerHTML='';$('quick-apply').disabled=false;clearAudio();recording(false);$('quick-input-form').hidden=false;$('quick-review').hidden=true;$('quick-result').hidden=true;$('quick-input-title').textContent='Что записать?';input.value='';update();}
  capture.open=(group=capture.context())=>{
    if(!dialog.open)opener=document.activeElement;sourceGroup=group || '';showInput();$('quick-input-context').textContent=sourceGroup ? `В группу «${sourceGroup}»` : 'Расскажи своими словами, Мунин разложит по задачам.';
    dialog.showModal();input.focus({preventScroll:true});
  };
  capture.showResult=(ids)=>{
    opener=document.activeElement;showInput();$('quick-input-form').hidden=true;$('quick-result').hidden=false;$('quick-input-title').textContent='Записано';$('quick-input-context').textContent='Можно поправить дело здесь, без поиска по списку.';
    $('quick-result-items').innerHTML=ids.map((id)=>{const task=capture.task(id);return task ? `<article class="capture-result-task"><div><strong>${e(task.title)}</strong><p>${e([task.group,task.meta,task.minutes ? task.minutes+' мин' : ''].filter(Boolean).join(' · '))}</p></div><button class="quiet-icon" data-capture-edit="${id}" aria-label="Изменить: ${e(task.title)}">${icon('edit')}</button></article>` : '';}).join('');dialog.showModal();
  };
  function parse(text) {
    const normalized=text.trim().replace(/^(надо|нужно|добавь(?: задачу)?)[\s:,-]*/i,'');
    const fragments=normalized.split(/[\n;]+|,\s*(?!\d)|\s+и\s+(?=(?:купить|позвонить|отправить|подготовить|записаться|проверить|разобрать|собрать|прочитать))/i);
    const pieces=[];
    fragments.filter(Boolean).forEach((piece)=>{if(pieces.length && /^(?:в\s+)?\d{1,2}[:.]\d{2}\b/.test(piece.trim()))pieces[pieces.length-1]+=' '+piece;else pieces.push(piece.trim());});
    const groupList=capture.groups(), sharedDay=/^завтра\s/i.test(normalized) ? '2026-10-06' : /^сегодня\s/i.test(normalized) ? '2026-10-05' : '';
    return pieces.map((piece)=>{
      const matches=capture.match(piece);
      if(/^(?:заверши|сделано|готово|отмени|верни)(?=\s|$)/i.test(piece)) {
        if(matches.length!==1)throw new Error(matches.length ? 'Нашлось несколько похожих дел. Уточни название.' : 'Не нашёл это дело. В примере изменения распознаются по полному названию задачи.');
        return {taskId:matches[0].id,title:matches[0].title,status:/^отмени/i.test(piece) ? 'cancelled' : /^верни/i.test(piece) ? 'todo' : 'done'};
      }
      const day=/завтра/i.test(piece) ? '2026-10-06' : /сегодня/i.test(piece) ? '2026-10-05' : sharedDay;
      const time=piece.match(/\b(?:в\s+)?([01]?\d|2[0-3])[:.]([0-5]\d)\b/), estimate=piece.match(/(?:^|\s)(?:на|за)\s+(\d{1,3})\s*мин(?:ут[уы]?)?(?=\s|$)/i);
      const explicit=groupList.find((group)=>piece.toLocaleLowerCase('ru').includes('#'+group.name.toLocaleLowerCase('ru')));
      let title=piece.replace(/(?:^|\s)(?:на|за)\s+\d{1,3}\s*мин(?:ут[уы]?)?(?=\s|$)/ig,'').replace(/(?:^|\s)(?:сегодня|завтра)(?=\s|$)/ig,' ').replace(/(?:^|\s)(?:в\s+)?(?:[01]?\d|2[0-3])[:.][0-5]\d(?=\s|$)/g,' ');
      if(explicit)title=title.replace('#'+explicit.name,'');
      title=title.replace(/^(надо|нужно)\s+/i,'').replace(/\s+/g,' ').replace(/[ .]+$/,'').trim();
      title=title.charAt(0).toLocaleUpperCase('ru')+title.slice(1);
      return {title,group:explicit?.name || sourceGroup || '',minutes:estimate ? Number(estimate[1]) : null,plannedDate:day,deadline:day && time ? `${day}T${time[1].padStart(2,'0')}:${time[2]}` : '',priority:/срочно/i.test(piece) ? 'urgent' : 'normal'};
    }).filter((draft)=>draft.title);
  }
  function apply() {
    const selected=drafts.filter((_,index)=>!$('quick-review-items').querySelector(`[data-capture-accept="${index}"]`) || $('quick-review-items').querySelector(`[data-capture-accept="${index}"]`).checked);
    if(!selected.length)return;
    const ids=capture.commit(selected);close();requestAnimationFrame(()=>capture.reveal(ids));
  }
  $('quick-input-form').addEventListener('submit',(event)=>{
    event.preventDefault();if(!input.value.trim())return;
    try {drafts=parse(input.value);} catch(error) {$('quick-input-error').textContent=error.message;$('quick-input-error').hidden=false;return;}
    if(!drafts.length){$('quick-input-error').textContent='Напиши, что нужно сделать.';$('quick-input-error').hidden=false;return;}
    if(drafts.some((draft)=>draft.title.length>500)){$('quick-input-error').textContent='Название получилось длинным. Раздели рассказ на несколько дел.';$('quick-input-error').hidden=false;return;}
    if(drafts.some((draft)=>draft.taskId)){
      $('quick-input-form').hidden=true;$('quick-review').hidden=false;$('quick-review-items').innerHTML=drafts.map((draft,index)=>`<label class="proposal-row"><input type="checkbox" data-capture-accept="${index}" checked><span><strong>${e(draft.title)}</strong><small>${draft.taskId ? {done:'Завершить',cancelled:'Отменить',todo:'Вернуть в список'}[draft.status] : 'Создать задачу'}</small></span></label>`).join('');$('quick-apply').focus();
    } else apply();
  });
  input.addEventListener('input',update);
  input.addEventListener('keydown',(event)=>{if(event.key==='Enter' && (event.ctrlKey || event.metaKey) && !event.isComposing){event.preventDefault();$('quick-input-form').requestSubmit();}});
  $('quick-apply').addEventListener('click',apply);
  $('quick-review-items').addEventListener('change',()=>{$('quick-apply').disabled=!$('quick-review-items').querySelector('input:checked');});
  $('quick-back').addEventListener('click',()=>{$('quick-review').hidden=true;$('quick-input-form').hidden=false;input.focus();});
  $('quick-another').addEventListener('click',()=>capture.open(sourceGroup));
  $('quick-voice-button').addEventListener('click',()=>recording(true));
  $('quick-voice-cancel').addEventListener('click',()=>{recording(false);input.focus();});
  $('quick-voice-finish').addEventListener('click',()=>{recording(false);input.value='Завтра позвонить Саше и купить продукты';update();$('quick-input-form').requestSubmit();});
  $('quick-file-button').addEventListener('click',()=>$('quick-file-input').click());
  $('quick-file-input').addEventListener('change',()=>{
    const file=$('quick-file-input').files[0];if(!file)return;clearAudio();$('quick-file-name').textContent=file.name;$('quick-file-preview').hidden=false;$('quick-file-example').hidden=false;
  });
  $('quick-file-remove').addEventListener('click',clearAudio);
  $('quick-file-example').addEventListener('click',()=>{input.value='Купить продукты и записаться к стоматологу';update();input.focus();});
  document.addEventListener('click',(event)=>{
    if(event.target.closest('[data-quick-input]'))capture.open();
    if(event.target.closest('[data-close-capture]'))close();
    const edit=event.target.closest('[data-capture-edit]');if(edit){close();openTaskEditor(Number(edit.dataset.captureEdit));}
  });
  let backdrop=false;
  const outside=(event)=>{const r=dialog.getBoundingClientRect();return event.clientX<r.left || event.clientX>r.right || event.clientY<r.top || event.clientY>r.bottom;};
  dialog.addEventListener('pointerdown',(event)=>{backdrop=event.target===dialog && outside(event);});
  dialog.addEventListener('click',(event)=>{if(backdrop && event.target===dialog && outside(event))close();backdrop=false;});
  dialog.addEventListener('close',()=>{recording(false);clearAudio();if(opener?.isConnected && opener.getClientRects().length)opener.focus({preventScroll:true});});
  const previousReset=reset;reset=()=>{if(dialog.open)close();previousReset();};
})();
