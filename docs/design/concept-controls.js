(() => {
  const controls=new Map();
  let opened=null, popup=null, typeahead='', typeTimer;
  const chevron='<svg class="icon" aria-hidden="true"><use href="#chevron"/></svg>';
  const check='<svg class="icon option-check" aria-hidden="true"><use href="#check"/></svg>';
  function optionIcon(option) {
    const key=option.dataset.groupIcon;
    return key && document.getElementById(`group-${key}`) ? `<svg class="icon option-group-icon" style="color:${option.dataset.groupColor}" aria-hidden="true"><use href="#group-${key}"/></svg>` : '';
  }
  function sync(control) {
    const {source,trigger}=control, option=source.selectedOptions[0];
    const content=`<span>${optionIcon(option || document.createElement('option'))}<span class="select-value-text">${escapeHtml(option?.textContent || 'Выбрать')}</span></span>${chevron}`;
    if(control.content!==content){trigger.innerHTML=content;control.content=content;}
    trigger.disabled=source.disabled;
    trigger.setAttribute('aria-invalid',source.getAttribute('aria-invalid') || 'false');
  }
  function close(restoreFocus=false) {
    if(!opened)return;clearTimeout(typeTimer);typeahead='';
    const trigger=opened.trigger;trigger.setAttribute('aria-expanded','false');trigger.removeAttribute('aria-controls');
    if(popup && typeof popup.hidePopover==='function' && popup.matches(':popover-open'))popup.hidePopover();popup?.remove();popup=null;opened=null;
    if(restoreFocus && trigger.isConnected)trigger.focus({preventScroll:true});
  }
  function choose(value) {
    const control=opened;if(!control)return;
    control.source.value=value;
    close(true);control.source.dispatchEvent(new Event('change',{bubbles:true}));
    refresh();
  }
  function options(query='') {
    if(!opened || !popup)return;
    const list=popup.querySelector('[role="listbox"]'),selected=opened.source.value;
    const values=[...opened.source.options].filter((option)=>!option.hidden && option.textContent.toLocaleLowerCase('ru').includes(query.toLocaleLowerCase('ru')));
    list.innerHTML=values.length ? values.map((option,index)=>`<button type="button" role="option" id="select-option-${index}" data-select-value="${escapeHtml(option.value)}" aria-selected="${option.value===selected}" ${option.disabled ? 'disabled' : ''}>${optionIcon(option)}<span>${escapeHtml(option.textContent)}</span>${option.value===selected ? check : ''}</button>`).join('') : '<p class="select-empty">Ничего не найдено</p>';
  }
  function focusOption(direction='selected') {
    const buttons=[...popup.querySelectorAll('[role="option"]:not(:disabled)')];
    const item=direction==='last' ? buttons.at(-1) : direction==='first' ? buttons[0] : buttons.find((button)=>button.getAttribute('aria-selected')==='true') || buttons[0];
    item?.focus({preventScroll:true});item?.scrollIntoView({block:'nearest'});
  }
  function place() {
    if(!opened || !popup)return;
    const rect=opened.trigger.getBoundingClientRect(),width=Math.min(Math.max(rect.width,216),document.documentElement.clientWidth-16);
    const bottom=innerWidth<1024 ? 80 : 8;
    const below=innerHeight-bottom-rect.bottom-6,above=rect.top-8;
    const topSide=below<Math.min(240,above),height=Math.max(80,Math.min(352,topSide ? above : below));
    popup.style.width=`${width}px`;popup.style.maxHeight=`${height}px`;
    popup.style.left=`${Math.max(8,Math.min(rect.left,document.documentElement.clientWidth-width-8))}px`;
    popup.style.top=`${topSide ? Math.max(8,rect.top-popup.getBoundingClientRect().height-6) : rect.bottom+6}px`;
    popup.dataset.side=topSide ? 'top' : 'bottom';
  }
  function open(control,keyboard=false,direction='selected') {
    if(opened===control){close(true);return;}close();opened=control;sync(control);
    popup=document.createElement('div');popup.className='select-popover';popup.setAttribute('popover','manual');
    popup.innerHTML=`${control.source.options.length>=8 ? '<label class="select-search"><svg class="icon" aria-hidden="true"><use href="#search"/></svg><input type="search" placeholder="Найти вариант" aria-label="Поиск вариантов"></label>' : ''}<div class="select-options" role="listbox" id="select-options" aria-label="${escapeHtml(control.label)}"></div>`;
    (control.source.closest('dialog[open]') || document.body).append(popup);
    if(typeof popup.showPopover==='function')popup.showPopover();else popup.removeAttribute('popover');
    control.trigger.setAttribute('aria-controls','select-options');control.trigger.setAttribute('aria-expanded','true');
    options();place();
    popup.addEventListener('click',(event)=>{const button=event.target.closest('[data-select-value]');if(button)choose(button.dataset.selectValue);});
    popup.querySelector('input')?.addEventListener('input',(event)=>{options(event.target.value);place();});
    if(popup.querySelector('input') && !keyboard)popup.querySelector('input').focus({preventScroll:true});else focusOption(direction);
  }
  function enhance(source) {
    if(controls.has(source) || source.dataset.iconSource!==undefined)return;
    const label=source.labels?.[0] || source.closest('label'),copy=label?.cloneNode(true);copy?.querySelectorAll('select,input,button').forEach((node)=>node.remove());
    const name=source.getAttribute('aria-label') || copy?.textContent.trim() || 'Выбор';
    const trigger=document.createElement('button');trigger.type='button';trigger.className='select-trigger';trigger.id=`${source.id}-trigger`;
    trigger.setAttribute('role','combobox');trigger.setAttribute('aria-haspopup','listbox');trigger.setAttribute('aria-expanded','false');trigger.setAttribute('aria-label',name);
    const wrapper=document.createElement('span');wrapper.className='custom-select';source.before(wrapper);wrapper.append(source,trigger);
    source.hidden=true;source.tabIndex=-1;source.setAttribute('aria-hidden','true');
    if(label)label.htmlFor=trigger.id;
    const control={source,trigger,label:name};controls.set(source,control);sync(control);
    trigger.addEventListener('click',(event)=>{event.preventDefault();open(control);});
    trigger.addEventListener('keydown',(event)=>{
      if(['ArrowDown','ArrowUp','Home','End'].includes(event.key)){event.preventDefault();open(control,true,event.key==='End' || event.key==='ArrowUp' ? 'last' : event.key==='Home' ? 'first' : 'selected');}
    });
    source.addEventListener('change',()=>sync(control));
    new MutationObserver(()=>sync(control)).observe(source,{childList:true,subtree:true,attributes:true,attributeFilter:['disabled','selected']});
  }
  function refresh() {document.querySelectorAll('select').forEach(enhance);controls.forEach(sync);}
  document.addEventListener('pointerdown',(event)=>{if(opened && !popup?.contains(event.target) && !opened.trigger.contains(event.target))close();},true);
  document.addEventListener('keydown',(event)=>{
    if(!opened)return;
    if(event.key==='Escape'){event.preventDefault();event.stopPropagation();close(true);return;}
    if(event.key===' ' && event.target.matches('[role="option"]')){event.preventDefault();choose(event.target.dataset.selectValue);return;}
    if(event.key==='Tab'){const trigger=opened.trigger;close();trigger.focus({preventScroll:true});return;}
    if(event.target.matches('input') && !['ArrowDown','ArrowUp'].includes(event.key))return;
    const buttons=[...popup.querySelectorAll('[role="option"]:not(:disabled)')],index=buttons.indexOf(document.activeElement);
    if(['ArrowDown','ArrowUp','Home','End'].includes(event.key)){
      event.preventDefault();const next=event.key==='Home' ? 0 : event.key==='End' ? buttons.length-1 : event.key==='ArrowDown' ? (index+1)%buttons.length : (index-1+buttons.length)%buttons.length;
      buttons[next]?.focus({preventScroll:true});buttons[next]?.scrollIntoView({block:'nearest'});return;
    }
    if(event.key.length===1 && !event.ctrlKey && !event.metaKey){
      event.preventDefault();clearTimeout(typeTimer);typeahead+=event.key.toLocaleLowerCase('ru');typeTimer=setTimeout(()=>{typeahead='';},650);
      const match=buttons.find((button)=>button.textContent.trim().toLocaleLowerCase('ru').startsWith(typeahead));match?.focus({preventScroll:true});match?.scrollIntoView({block:'nearest'});
    }
  },true);
  window.addEventListener('resize',()=>close());
  document.addEventListener('scroll',()=>{if(opened && !opened.trigger.getBoundingClientRect().height)close();else place();},true);
  document.querySelectorAll('dialog').forEach((dialog)=>{dialog.addEventListener('close',()=>close());dialog.addEventListener('cancel',()=>close());});
  document.addEventListener('click',(event)=>{if(event.target.closest('button[data-page],#reset,[data-close-new],[data-close-group]'))close();refresh();});
  const beforeFill=window.fillTaskFields;
  window.fillTaskFields=(task)=>{beforeFill(task);refresh();};
  const beforeRender=render;
  render=(...args)=>{beforeRender(...args);refresh();};
  refresh();
})();
