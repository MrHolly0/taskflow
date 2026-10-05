(() => {
  const $ = (id) => document.getElementById(id);
  const e = escapeHtml;
  const labels = {focus: 'Сейчас', assistant: 'Ассистент', all: 'Все задачи', board: 'Доска', groups: 'Группы', stats: 'Статистика', settings: 'Настройки', integrations: 'Интеграции', more: 'Ещё'};
  const statuses = {todo: 'К выполнению', started: 'В работе', done: 'Готово', cancelled: 'Отменено'};
  const colors = ['#4b78bb', '#8e69af', '#4d9265', '#b87942', '#bd6586', '#589499', '#b08d34', '#7a7f89'];
  const colorNames = ['Синий', 'Фиолетовый', 'Зелёный', 'Оранжевый', 'Розовый', 'Бирюзовый', 'Жёлтый', 'Серый'];
  let groups = [], expandedGroup = null, editingGroup = null, groupColor = colors[0];
  let period = 'all', search = '', statusFilter = '', groupFilter = '', lane = 'todo', selectedDay = 6;
  let knownTasks = new Map(), identities = {}, binding = null;
  let listPage = 1, selecting = false, selectedIds = new Set(), historyOpen = false, visibleRecords = [];
  let folderSearch = '', folderOpened = false, folderPage = 1, folderHistory = false, folderLimit = 12;
  let weeklyGoal = 5, receiptDay = null, goalEditorOpen = false, statsDetailsOpen = false;
  let menuState = null, menuOpener = null, folderDragId = null, folderHoverName = null, folderHoverTimer = null, denseExample = false;
  const settings = {name: 'Саша', timezone: 'Europe/Moscow', theme: 'light'};
  const dates = ['2026-09-29', '2026-09-30', '2026-10-01', '2026-10-02', '2026-10-03', '2026-10-04', '2026-10-05'];
  const shortDays = ['Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс', 'Пн'];
  const dateLabel = (value) => new Intl.DateTimeFormat('ru-RU', {day: 'numeric', month: 'short', timeZone: 'UTC'}).format(new Date(`${value.slice(0, 10)}T12:00:00Z`));
  const active = (task) => !task.archived && !['done', 'cancelled'].includes(task.status);
  const groupOptions = () => `<option value="">Без группы</option>${groups.map((group) => `<option>${e(group.name)}</option>`).join('')}`;
  const statusOptions = (includeCancelled = true) => Object.entries(statuses).filter(([key]) => includeCancelled || key !== 'cancelled').map(([key, value]) => `<option value="${key}">${value}</option>`).join('');
  const head = (title, subtitle, action = '') => `<header class="page-head"><div><h1>${title}</h1>${subtitle ? `<p>${subtitle}</p>` : ''}</div>${action}</header>`;
  const addButton = (label, data) => `<button class="action primary" ${data}>${icon('plus')}${label}</button>`;
  const section = (id, content, extra = '') => `<section class="content secondary-page ${extra}" id="${id}-page" hidden>${content}</section>`;
  const field = (label, id, type = 'text', attrs = '') => `<label class="form-field" for="${id}">${label}<input class="field-control" id="${id}" type="${type}" ${attrs}></label>`;
  const toggle = (label, id, checked = false, note = '') => `<label class="setting-row setting-toggle"><span>${label}${note ? `<small>${note}</small>` : ''}</span><input class="switch" role="switch" type="checkbox" id="${id}" ${checked ? 'checked' : ''}></label>`;
  document.querySelector('.workspace').insertAdjacentHTML('beforeend', [
    section('all', `${head('Все задачи', '', addButton('Добавить', 'data-new-task'))}
      <form class="inline-form" id="new-task-form" hidden><h2>Новая задача</h2><div class="form-grid">${field('Название', 'new-task-title', 'text', 'required maxlength="500"')}<label>Группа<select class="field-control" id="new-task-group"></select></label></div><div class="actions"><button class="action primary">Добавить</button><button class="action" type="button" data-close-new>Отмена</button></div></form>
      <div class="task-workbar"><div class="filter-strip" aria-label="Период задач">${[['now','Сейчас'],['today','Сегодня'],['week','Неделя'],['all','Все']].map(([key, label]) => `<button data-period="${key}" aria-pressed="${key === 'all'}">${label}</button>`).join('')}</div><label class="search-field">${icon('search')}<input type="search" id="task-search" aria-label="Поиск задач" placeholder="Найти задачу"></label><button class="tool-button filter-button" data-toggle-filters aria-expanded="false" aria-label="Фильтры">${icon('settings')}<span>Фильтры</span></button><button class="tool-button selection-button" data-toggle-selection aria-pressed="false" aria-label="Выбрать несколько задач">${icon('check')}<span>Выбрать</span></button></div>
      <div class="list-filters" id="list-filters" hidden><label>Группа<select class="field-control" id="list-group" aria-label="Фильтр по группе"></select></label><label>Статус<select class="field-control" id="list-status" aria-label="Фильтр по статусу"><option value="">Все статусы</option>${statusOptions()}</select></label><label>Порядок<select class="field-control" id="list-order"><option value="default">По статусу</option><option value="deadline">По сроку</option><option value="title">По названию</option></select></label></div>
      <div class="list-summary"><span id="list-caption"></span><span class="mouse-tip">ПКМ или ⋯: действия</span><span class="touch-tip">Действия в ⋯</span></div><div class="mass-toolbar" id="mass-toolbar" hidden></div><div id="all-list"></div><nav class="list-pager" id="task-pagination" aria-label="Страницы задач"></nav>`),
    section('board', `${head('Доска', 'Три состояния. Двигай задачи в своём темпе.', addButton('Задача', 'data-new-task'))}<div class="filter-strip board-mobile-tabs" id="lane-tabs" aria-label="Колонка доски"></div><div class="board-grid" id="board-grid"></div>`),
    section('groups', `${head('Группы', '', addButton('Группа', 'data-new-group'))}<form class="inline-form" id="group-form" hidden><h2 id="group-form-heading">Новая группа</h2><div class="form-grid">${field('Название', 'group-name', 'text', 'required maxlength="100"')}<label>Значок<select class="field-control" id="group-symbol"><option value="groups">Папка</option><option value="list">Список</option><option value="focus">Фокус</option><option value="clock">Часы</option></select></label><div class="wide"><p class="page-note">Цвет метки</p><div class="color-choices">${colors.map((color, index) => `<button type="button" class="color-choice" data-group-color="${color}" style="--group-color:${color}" aria-label="${colorNames[index]}" aria-pressed="${index === 0}"><span></span></button>`).join('')}</div></div></div><p class="field-error" id="group-error" role="alert" hidden></p><div class="actions"><button class="action primary">Сохранить</button><button class="action" type="button" data-close-group>Отмена</button></div></form><div class="group-hints"><span id="groups-count"></span><span class="mouse-tip">ЛКМ: открыть · ПКМ: управлять · задачи можно перетаскивать</span><span class="touch-tip">Управление в ⋯</span></div><div id="group-list" class="groups-workspace"><aside class="folder-index"><label class="search-field">${icon('search')}<input id="folder-search" type="search" aria-label="Найти группу" placeholder="Найти группу"></label><div id="folder-rail" class="folder-rail"></div><div id="folder-index-caption" class="index-caption"></div></aside><div class="folder-stage"><div id="group-canvas"></div><div id="folder-drag-rest" class="folder-drag-rest" hidden></div><div id="folder-hover-preview" class="folder-hover-preview" hidden></div></div></div>`),
    section('stats', `${head('Статистика', '29 сентября — 5 октября · твоя неделя')}<div class="progress-layout"><div><section class="week-intent"><p class="eyebrow">Ориентир недели</p><h2 id="goal-heading"></h2><div id="goal-track"></div><div class="intent-caption"><span id="goal-caption"></span><button data-edit-goal>Изменить цель</button></div><form id="goal-editor" class="goal-editor" hidden><label for="goal-value">Сколько дел хочешь завершить за неделю?</label><div class="toolbar"><input id="goal-value" class="field-control" type="number" min="1" max="1000" required value="5" aria-label="Цель недели"><button class="action primary">Сохранить</button><button type="button" class="action" data-close-goal>Отмена</button></div></form></section><div id="week-chart" class="week-rhythm" aria-label="Завершения по дням"></div><div class="receipt-heading"><h2 id="receipt-title">Что уже позади</h2><button data-all-receipts>Вся неделя</button></div><div id="progress-receipts"></div><div class="stats-audit"><button data-toggle-stat-details aria-expanded="false">Показать числа и даты</button><div id="stats-details" hidden></div></div></div><aside class="progress-side"><h2>Что продвинулось</h2><div id="group-progress"></div><div id="stats-next-step"></div></aside></div>`),
    section('settings', `${head('Настройки', 'Подстрой Мунина под себя.')}<p class="page-note">Изменения сохраняются в этом примере до сброса страницы.</p><section class="settings-section"><h2>Профиль</h2><form id="profile-form"><label class="setting-row"><span>Как к тебе обращаться</span><div class="toolbar"><input id="profile-name" class="field-control" aria-label="Имя" maxlength="64" required value="Саша"><button class="action primary">Сохранить</button></div></label><p class="status-note" id="profile-status" role="status"></p></form><label class="setting-row"><span>Часовой пояс<small>Даты и напоминания в приложении</small></span><select class="field-control" id="timezone"><option value="Europe/Moscow">Москва · UTC+3</option><option value="Europe/Kaliningrad">Калининград · UTC+2</option><option value="Asia/Yekaterinburg">Екатеринбург · UTC+5</option><option value="Asia/Novosibirsk">Новосибирск · UTC+7</option><option value="Asia/Vladivostok">Владивосток · UTC+10</option></select></label></section>
      <section class="settings-section"><h2>Напоминания</h2>${toggle('Получать напоминания', 'notify-master', true)}${toggle('В Telegram', 'notify-telegram', true)}${toggle('На почту', 'notify-email', false, 'Нужен подключённый адрес')}${toggle('В браузере', 'notify-push', false, 'Разрешения и доставка в примере не подключены')}<label class="setting-row"><span>До срока задачи</span><select class="field-control" id="reminder-lead"><option>За 15 минут</option><option selected>За 30 минут</option><option>За час</option><option>За 2 часа</option><option>За день</option></select></label>${toggle('Повторять для срочных задач', 'notify-urgent', false, 'Каждые 15 минут до завершения')}</section>
      <section class="settings-section"><h2>Внешний вид и голос</h2><div class="setting-row"><span>Тема</span><div class="filter-strip">${[['light','День'],['dark','Ночь'],['system','Система']].map(([key, name]) => `<button data-theme-choice="${key}" aria-pressed="${key === 'light'}">${name}</button>`).join('')}</div></div><label class="setting-row"><span>Запись на компьютере</span><select class="field-control" id="voice-desktop"><option>Заканчивать после паузы</option><option>Начать и остановить кнопкой</option></select></label><label class="setting-row"><span>Запись на телефоне</span><select class="field-control" id="voice-mobile"><option>Удерживать кнопку</option><option>Начать и остановить кнопкой</option><option>Заканчивать после паузы</option></select></label></section>
      <section class="settings-section"><h2>Завершённые задачи</h2><label class="setting-row"><span>Убирать из списков<small>Выполненные и отменённые задачи</small></span><select class="field-control" id="cleanup-period"><option>Не убирать</option><option>Через 7 дней</option><option>Через 14 дней</option><option selected>Через 30 дней</option><option>Через 90 дней</option></select></label><div class="setting-row"><span>Очистить списки сейчас<small>Активные задачи останутся на месте</small></span><button class="action" data-cleanup>Убрать завершённые</button></div><div class="inline-form" id="cleanup-confirm" hidden><h2>Убрать завершённые и отменённые?</h2><p class="page-note">В примере они скроются из списка и доски. Итоги недели сохранятся.</p><div class="actions"><button class="action primary" data-confirm-cleanup>Убрать</button><button class="action" data-cancel-cleanup>Отмена</button></div></div></section>`, 'settings-content'),
    section('integrations', `${head('Интеграции', 'Один аккаунт, несколько способов входа.')}<div id="access-note" class="access-note"></div><div id="provider-list"></div><div class="identity-help"><p id="identity-protection"></p><button data-toggle-identity-help aria-expanded="false">Если у меня уже есть другой аккаунт?</button><p id="identity-help-text" hidden>При подключении почты или Telegram Мунин предложит перенести задачи из прежнего аккаунта. Число задач и групп появится до подтверждения.</p><p>Деморежим: письма и звонки не отправляются. Код почты 123456.</p></div>`, 'integrations-content'),
    section('more', `${head('Ещё', 'Остальное — когда понадобится.')}<div>${[['stats','stats','Статистика','Обзор недели'],['settings','settings','Настройки','Текущая версия приложения'],['integrations','plug','Интеграции','Telegram, почта и телефон']].map(([page, symbol, title, note]) => `<button class="menu-link" data-page="${page}">${icon(symbol)}<span>${title}<small>${note}</small></span>${icon('arrow')}</button>`).join('')}</div>`)
  ].join(''));

  document.body.insertAdjacentHTML('beforeend', '<div id="quick-menu" class="quick-menu" role="menu" aria-label="Быстрые действия" hidden></div>');
  document.querySelector('.studybar').insertAdjacentHTML('beforeend', '<button class="demo-density" id="large-example">120 задач и 16 групп</button>');

  $('edit-form').querySelector('.actions').insertAdjacentHTML('beforebegin', `<div class="form-grid details-fields"><label>Статус<select class="field-control" id="detail-status">${statusOptions()}</select></label><label>Группа<select class="field-control" id="detail-group"></select></label><label>Приоритет<select class="field-control" id="detail-priority"><option value="normal">Обычный</option><option value="urgent">Срочный</option></select></label>${field('Оценка, минут', 'detail-minutes', 'number', 'min="1" max="1440" placeholder="Без оценки"')}${field('Срок завершения', 'detail-deadline', 'datetime-local')}${field('День исполнения', 'detail-planned', 'date')}${field('Напомнить', 'detail-reminder', 'datetime-local')}<label class="wide">Описание<textarea class="field-control" id="detail-description" maxlength="2000"></textarea></label><small class="wide">День исполнения — когда берёшься за задачу. Срок — когда нужно закончить.</small></div>`);

  function taskMeta(task) {
    const parts = [];
    const relativeDate = (value) => ({'2026-10-05':'сегодня','2026-10-06':'завтра','2026-10-04':'вчера'}[value.slice(0,10)] || dateLabel(value));
    if (task.deadline) parts.push(`Срок: ${relativeDate(task.deadline)}, ${task.deadline.slice(11,16)}`);
    if (task.plannedDate) parts.push(`День исполнения: ${relativeDate(task.plannedDate)}`);
    return parts.join(' · ') || 'Без срока';
  }
  function observeTasks() {
    tasks.forEach((task) => {
      const previous = knownTasks.get(task.id);
      if (task.createdDay === undefined) task.createdDay = 6;
      if (previous && previous.status !== 'done' && task.status === 'done') task.completedDay = 6;
      if (previous?.status === 'done' && task.status !== 'done') delete task.completedDay;
      if (task.status === 'moved' && previous?.status !== 'moved') { task.plannedDate = '2026-10-06'; task.meta = taskMeta(task); }
      if (previous && previous.title !== task.title && task.status === 'done') completedToday = completedToday.map((title) => title === previous.title ? task.title : title);
      knownTasks.set(task.id, {status: task.status, title: task.title});
    });
  }
  function setTaskStatus(task, status) {
    const old = task.status;
    if (old === status) return;
    if (old === 'done' && task.completedDay === 6) { counts.done = Math.max(0, counts.done - 1); completedToday = completedToday.filter((title) => title !== task.title && title !== knownTasks.get(task.id)?.title); }
    if (status === 'done') { counts.done++; completedToday.push(task.title); }
    if (status === 'started') counts.started++;
    task.status = status;
  }
  window.fillTaskFields = (task) => {
    $('detail-status').value = task.status === 'moved' ? 'todo' : task.status;
    $('detail-group').innerHTML = groupOptions(); $('detail-group').value = task.group || '';
    $('detail-priority').value = task.priority || 'normal'; $('detail-minutes').value = task.minutes ?? '';
    $('detail-deadline').value = task.deadline || ''; $('detail-planned').value = task.plannedDate || '';
    $('detail-reminder').value = task.reminder || ''; $('detail-description').value = task.description || '';
  };
  window.applyTaskFields = (task) => {
    setTaskStatus(task, $('detail-status').value);
    task.group = $('detail-group').value; task.priority = $('detail-priority').value;
    task.minutes = $('detail-minutes').value ? Number($('detail-minutes').value) : null;
    task.deadline = $('detail-deadline').value; task.plannedDate = $('detail-planned').value;
    task.reminder = $('detail-reminder').value; task.description = $('detail-description').value.trim(); task.meta = taskMeta(task);
  };
  const dot = (name) => `<i class="group-dot" style="background:${groups.find((group) => group.name === name)?.color || colors[7]}"></i>`;
  const shortDeadline = (task) => task.deadline ? `${({'2026-10-05':'Сегодня','2026-10-06':'Завтра','2026-10-04':'Вчера'}[task.deadline.slice(0,10)] || dateLabel(task.deadline))}, ${task.deadline.slice(11,16)}` : 'Без срока';
  const row = (task) => {
    const selected = selecting ? selectedIds.has(task.id) : task.status === 'done';
    const label = selecting ? `Выбрать: ${task.title}` : `${['done','cancelled'].includes(task.status) ? 'Вернуть' : 'Завершить'}: ${task.title}`;
    const context = `${task.status === 'started' ? '<span class="working-mark"><i></i>В работе</span>' : task.status === 'cancelled' ? '<span>Отменено</span>' : ''}${task.priority === 'urgent' ? '<span class="critical">Срочно</span>' : ''}${task.minutes ? `<span>${task.minutes} мин</span>` : ''}${task.plannedDate ? `<span>Исполнение: ${task.plannedDate === '2026-10-05' ? 'сегодня' : task.plannedDate === '2026-10-06' ? 'завтра' : dateLabel(task.plannedDate)}</span>` : ''}`;
    return `<article class="list-record ${task.status === 'done' ? 'is-done' : ''} ${selectedIds.has(task.id) && selecting ? 'is-selected' : ''}" data-record-id="${task.id}"><button class="completion-hit" role="checkbox" aria-checked="${selected}" data-complete-record="${task.id}" aria-label="${e(label)}" title="${e(label)}"><span class="completion-ring">${icon('check')}</span></button><button class="record-open" data-open-task="${task.id}" title="${e(task.title)}"><span class="record-title">${e(task.title)}</span>${context ? `<span class="record-context">${context}</span>` : ''}</button><div class="record-details"><span class="record-group" title="${e(task.group || 'Без группы')}">${task.group ? dot(task.group) : ''}${e(task.group || 'Без группы')}</span><span class="record-deadline ${task.deadline ? '' : 'is-undated'} ${task.deadline && task.deadline < '2026-10-05' && active(task) ? 'critical' : ''}" title="Срок завершения: ${e(shortDeadline(task))}">${e(shortDeadline(task))}</span></div><button class="record-menu" data-task-menu="${task.id}" aria-label="Действия: ${e(task.title)}" aria-haspopup="menu" aria-expanded="false">${icon('more')}</button></article>`;
  };
  function listMatches(task) {
    if (task.archived || !task.title.toLocaleLowerCase('ru').includes(search.toLocaleLowerCase('ru')) || groupFilter && task.group !== groupFilter || statusFilter && (task.status === 'moved' ? 'todo' : task.status) !== statusFilter) return false;
    if (period === 'all') return true;
    if (!active(task)) return false;
    if (period === 'now') return task.priority === 'urgent' || task.status === 'started' || task.deadline && task.deadline < '2026-10-05';
    return !task.deadline || task.deadline <= (period === 'today' ? '2026-10-05T23:59' : '2026-10-11T23:59');
  }
  function renderList() {
    $('list-group').innerHTML = `<option value="">Все группы</option>${groups.map((group) => `<option>${e(group.name)}</option>`).join('')}`; $('list-group').value = groupFilter;
    const matched = tasks.filter(listMatches), pending = matched.filter(active), history = matched.filter((task) => !active(task));
    const showHistory = historyOpen || !!search || ['done','cancelled'].includes(statusFilter);
    const items = matched.filter((task) => active(task) || showHistory);
    const rank = {started:0,todo:1,moved:1,done:2,cancelled:3};
    const order = $('list-order').value;
    items.sort((a,b) => order === 'title' ? a.title.localeCompare(b.title,'ru') : order === 'deadline' ? (a.deadline || '9999').localeCompare(b.deadline || '9999') || a.id-b.id : rank[a.status]-rank[b.status] || b.id-a.id);
    const pageSize = 20, pageCount = Math.max(1, Math.ceil(items.length/pageSize)); listPage = Math.min(pageCount, Math.max(1,listPage));
    visibleRecords = items.slice((listPage-1)*pageSize,listPage*pageSize);
    $('list-caption').textContent = `${pending.length} активных${history.length ? ` · ${history.length} в истории` : ''}`;
    $('list-caption').title = period === 'today' || period === 'week' ? 'Контекст включает просроченные задачи и задачи без срока' : `${matched.length} ${doneWord(matched.length)}`;
    $('all-list').classList.toggle('is-selecting',selecting);
    $('all-list').innerHTML = matched.length ? `${visibleRecords.length ? '<div class="record-head"><span>Задача</span><span>Группа</span><span>Срок</span></div>' + visibleRecords.map(row).join('') : '<p class="page-note">Активных задач нет. Завершённые и отменённые доступны в истории.</p>'}${history.length && !search && !['done','cancelled'].includes(statusFilter) ? `<button class="history-toggle" data-toggle-history aria-expanded="${historyOpen}">${icon('chevron')}Завершённые и отменённые · ${history.length}</button>` : ''}` : '<div class="empty-state"><h2>Ничего не найдено</h2><p>Убери фильтры или попробуй другой запрос.</p><button class="action" data-clear-filters>Сбросить фильтры</button></div>';
    $('task-pagination').innerHTML = items.length ? `<span>${(listPage-1)*pageSize+1}–${Math.min(listPage*pageSize,items.length)} из ${items.length}</span><button class="quiet-icon previous" data-list-page="${listPage-1}" aria-label="Предыдущая страница задач" ${listPage===1 ? 'disabled' : ''}>${icon('arrow')}</button><span>${listPage} / ${pageCount}</span><button class="quiet-icon" data-list-page="${listPage+1}" aria-label="Следующая страница задач" ${listPage===pageCount ? 'disabled' : ''}>${icon('arrow')}</button>` : '';
    selectedIds.forEach((id) => { if (!tasks.some((task) => task.id===id && !task.archived)) selectedIds.delete(id); });
    $('mass-toolbar').hidden = !selecting;
    $('mass-toolbar').innerHTML = selecting ? `<span class="mass-count">Выбрано ${selectedIds.size}</span><button data-select-page>${visibleRecords.length && visibleRecords.every((task) => selectedIds.has(task.id)) ? 'Снять на странице' : 'Выбрать страницу'}</button><button data-bulk-done ${selectedIds.size ? '' : 'disabled'}>Завершить</button><button data-bulk-move ${selectedIds.size ? '' : 'disabled'}>В группу</button><button data-toggle-selection>Готово</button>` : '';
    document.querySelector('.selection-button').setAttribute('aria-pressed',String(selecting));
  }
  function renderBoard() {
    const columns = ['todo', 'started', 'done'];
    const matches = (task, status) => !task.archived && (task.status === 'moved' ? 'todo' : task.status) === status;
    $('lane-tabs').innerHTML = columns.map((status) => `<button data-lane="${status}" aria-pressed="${lane === status}">${statuses[status]} · ${tasks.filter((task) => matches(task,status)).length}</button>`).join('');
    $('board-grid').innerHTML = columns.map((status) => {
      const items = tasks.filter((task) => matches(task,status));
      return `<section class="board-lane ${lane === status ? 'is-selected' : ''}" data-drop-status="${status}"><header class="lane-title"><h2>${statuses[status]}</h2><span>${items.length}</span></header>${items.length ? items.map((task) => `<article class="board-task" draggable="true" data-drag-task="${task.id}"><button data-open-task="${task.id}">${e(task.title)}</button><div class="row-meta">${task.group ? `<span>${dot(task.group)}${e(task.group)}</span>` : ''}<span>${e(taskMeta(task))}</span></div><select class="field-control" data-task-status="${task.id}" aria-label="Статус: ${e(task.title)}">${Object.entries(statuses).filter(([key]) => key !== 'cancelled').map(([key, name]) => `<option value="${key}" ${key === status ? 'selected' : ''}>${name}</option>`).join('')}</select></article>`).join('') : '<p class="board-empty">Задач нет. Можно перенести сюда задачу из другой колонки.</p>'}</section>`;
    }).join('');
  }
  function folderEmblem(group, open = false) {
    return `<svg class="folder-emblem" style="--folder-color:${group?.color || colors[7]}" aria-hidden="true" viewBox="0 0 44 36"><path class="folder-rear" d="M3 29V7a3 3 0 0 1 3-3h10l4 4h18a3 3 0 0 1 3 3v18a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3Z"/><path class="folder-front" d="M3 13h38l-3 18H6Z" ${open ? 'style="transform:translateY(2px) scaleY(.8)"' : ''}/>${group?.symbol && group.symbol!=='groups' ? `<svg class="folder-custom" x="14" y="14" width="16" height="16" viewBox="0 0 24 24"><use href="#${group.symbol}"/></svg>` : ''}</svg>`;
  }
  function folderTask(task, preview = false) {
    return `<article class="folder-task ${task.status==='done' ? 'is-done' : ''}" data-record-id="${task.id}" data-group-task="${task.id}" draggable="false"><div class="folder-task-top"><button class="completion-hit" role="checkbox" aria-checked="${task.status==='done'}" data-complete-record="${task.id}" aria-label="${['done','cancelled'].includes(task.status) ? 'Вернуть' : 'Завершить'}: ${e(task.title)}"><span class="completion-ring">${icon('check')}</span></button><button class="record-open" data-open-task="${task.id}"><span class="record-title">${e(task.title)}</span>${task.status==='started' ? '<span class="record-context">В работе</span>' : task.status==='cancelled' ? '<span class="record-context">Отменено</span>' : ''}</button><button class="record-menu" data-task-menu="${task.id}" aria-label="Действия: ${e(task.title)}" aria-haspopup="menu">${icon('more')}</button></div><div class="folder-task-bottom"><span>${e(shortDeadline(task))}${task.minutes ? ` · ${task.minutes} мин` : ''}</span><span class="drag-handle" draggable="false" title="Перетащить в другую группу"><svg class="drag-grip" aria-hidden="true" viewBox="0 0 12 18"><g fill="currentColor"><circle cx="3" cy="4" r="1"/><circle cx="9" cy="4" r="1"/><circle cx="3" cy="9" r="1"/><circle cx="9" cy="9" r="1"/><circle cx="3" cy="14" r="1"/><circle cx="9" cy="14" r="1"/></g></svg></span></div></article>`;
  }
  function folderCanvas(group, preview = false) {
    if (!group) return '<div class="empty-state"><h2>Создай первую группу</h2><p>В ней можно собрать связанные задачи.</p></div>';
    const all = tasks.filter((task) => !task.archived && task.group===group.name).sort((a,b)=>b.id-a.id), pending=all.filter(active), historical=all.filter((task)=>!active(task));
    const items = preview || !folderHistory ? pending : historical, limit=12, pages=Math.max(1,Math.ceil(items.length/limit));
    if (!preview) folderPage=Math.min(pages,Math.max(1,folderPage));
    const page = preview ? 1 : folderPage;
    return `${preview ? '' : `<button class="folder-back" data-folder-back>${icon('arrow')}Все группы</button>`}<header class="folder-stage-head">${folderEmblem(group,true)}<div><h2 id="${preview ? 'group-preview-title' : 'group-view-title'}" tabindex="-1">${e(group.name)}</h2><p>${preview ? 'Отпусти, чтобы перенести сюда' : `${pending.length} активных · ${historical.length} в истории`}</p></div>${preview ? '' : `<button class="record-menu" data-group-menu="${e(group.name)}" aria-label="Управлять группой ${e(group.name)}" aria-haspopup="menu">${icon('more')}</button>`}</header>${preview ? '' : `<div class="folder-tools"><button class="action" data-new-task data-task-group="${e(group.name)}">${icon('plus')} Задача</button><button class="text-action" data-folder-history>${folderHistory ? 'Активные' : 'Завершённые и отменённые'}</button></div>`}<div class="folder-task-grid">${items.slice((page-1)*limit,page*limit).map((task)=>folderTask(task,preview)).join('')}</div>${items.length ? pages>1 && !preview ? `<nav class="list-pager" aria-label="Страницы задач группы"><span>${(page-1)*limit+1}–${Math.min(page*limit,items.length)} из ${items.length}</span><button class="quiet-icon previous" data-folder-page="${page-1}" aria-label="Предыдущая страница группы" ${page===1 ? 'disabled' : ''}>${icon('arrow')}</button><button class="quiet-icon" data-folder-page="${page+1}" aria-label="Следующая страница группы" ${page===pages ? 'disabled' : ''}>${icon('arrow')}</button></nav>` : '' : `<div class="empty-state"><h2>${preview ? 'Здесь есть место' : folderHistory ? 'История пока пуста' : 'Папка свободна'}</h2><p>${preview ? 'Задача появится в этой группе после переноса.' : folderHistory ? 'Завершённые задачи появятся здесь.' : 'Добавь задачу или перенеси её из другой группы.'}</p></div>`}`;
  }
  function renderGroups() {
    if (folderDragId !== null) return;
    if (!groups.some((group)=>group.name===expandedGroup)) expandedGroup=groups[0]?.name || null;
    const filtered=groups.filter((group)=>group.name.toLocaleLowerCase('ru').includes(folderSearch.toLocaleLowerCase('ru'))), visible=folderSearch ? filtered : filtered.slice(0,folderLimit);
    $('groups-count').textContent=`${groups.length} ${groups.length===1 ? 'группа' : groups.length>=2 && groups.length<=4 ? 'группы' : 'групп'}`;
    $('folder-rail').innerHTML=visible.length ? visible.map((group)=>{const pending=tasks.filter((task)=>active(task) && task.group===group.name).length;return `<button class="folder-tile" data-open-folder="${e(group.name)}" data-drop-folder="${e(group.name)}" aria-expanded="${expandedGroup===group.name}" title="${e(group.name)} · ПКМ: управление" style="--folder-color:${group.color}">${folderEmblem(group)}<span class="folder-count">${pending}</span><strong>${e(group.name)}</strong><small>${pending ? 'в планах' : 'нет активных задач'}</small></button>`;}).join('') : '<p class="page-note">Группа не найдена.</p>';
    $('folder-index-caption').innerHTML=`<span>${visible.length} из ${filtered.length}</span>${visible.length<filtered.length ? '<button data-more-folders>Ещё группы</button>' : ''}`;
    $('group-list').classList.toggle('folder-focused',folderOpened);
    const group=groups.find((item)=>item.name===expandedGroup);
    $('group-canvas').innerHTML=folderCanvas(group); if(group) document.querySelector('.folder-stage').dataset.dropFolder=group.name;
  }
  function renderStats() {
    const days=dates.map((date,index)=>({date,created:tasks.filter((task)=>task.createdDay===index).length,done:tasks.filter((task)=>task.status==='done' && task.completedDay===index).length}));
    const completed=tasks.filter((task)=>task.status==='done' && task.completedDay>=0 && task.completedDay<=6), done=completed.length, remaining=Math.max(0,weeklyGoal-done), ratio=Math.min(1,done/weeklyGoal);
    $('goal-heading').textContent=remaining===0 ? 'Твой ориентир на неделю закрыт.' : done===0 ? 'Начни с одного дела.' : remaining===1 ? 'Ещё одно дело до цели.' : `Ещё ${remaining} ${doneWord(remaining)} до цели.`;
    $('goal-track').innerHTML=`<div class="intent-track" role="progressbar" aria-label="Ориентир недели" aria-valuemin="0" aria-valuemax="${weeklyGoal}" aria-valuenow="${Math.min(done,weeklyGoal)}" aria-valuetext="Завершено ${done} из ${weeklyGoal}"><div class="intent-fill" style="--progress:${ratio}"></div><div class="intent-ticks" style="--segments:${Math.min(10,weeklyGoal)}">${Array.from({length:Math.min(10,weeklyGoal)},()=>'<i></i>').join('')}</div>`;
    $('goal-caption').textContent=`${done} из ${weeklyGoal} дел завершено`;
    $('goal-editor').hidden=!goalEditorOpen; if(!goalEditorOpen) $('goal-value').value=weeklyGoal;
    $('week-chart').innerHTML=days.map((day,index)=>`<button class="rhythm-day ${day.done ? 'has-done' : ''}" data-chart-day="${index}" aria-pressed="${receiptDay===index}" aria-label="${dateLabel(day.date)}: завершено ${day.done}" title="${dateLabel(day.date)}: ${day.done} готово"><span>${shortDays[index]}</span><span class="rhythm-stamp">${day.done || '·'}</span><span>${day.date.slice(8).replace(/^0/,'')}</span></button>`).join('');
    const receipts=completed.filter((task)=>receiptDay===null || task.completedDay===receiptDay).sort((a,b)=>b.completedDay-a.completedDay || a.id-b.id);
    $('receipt-title').textContent=receiptDay===null ? 'Что уже позади' : `Сделано ${dateLabel(dates[receiptDay])}`;
    $('progress-receipts').innerHTML=receipts.length ? receipts.map((task)=>`<article class="progress-receipt"><span class="receipt-mark" aria-hidden="true">ГОТОВО</span><button data-open-task="${task.id}">${e(task.title)}<small>${e(task.group || 'Без группы')}</small></button><time datetime="${dates[task.completedDay]}">${dateLabel(dates[task.completedDay])}</time></article>`).join('') : `<div class="empty-state"><h2>${receiptDay===null ? 'Здесь будут твои результаты' : 'В этот день без завершений'}</h2><p>${receiptDay===null ? 'Каждое завершённое дело остаётся в истории недели.' : 'Открой всю неделю, чтобы увидеть другие дни.'}</p></div>`;
    $('group-progress').innerHTML=groups.map((group)=>{const wins=completed.filter((task)=>task.group===group.name).sort((a,b)=>b.completedDay-a.completedDay);return wins.length ? `<button class="group-progress-row" data-stat-group="${e(group.name)}"><strong>${dot(group.name)}${e(group.name)}</strong><span>${wins.length} готово</span><p>${e(wins[0].title)}</p></button>` : '';}).join('') || '<p class="page-note">Завершения покажут, какие направления ты продвинул.</p>';
    const next=tasks.find((task)=>active(task) && task.status!=='started' && task.status!=='moved' && task.priority==='urgent') || tasks.find((task)=>active(task) && task.status!=='started' && task.status!=='moved') || tasks.find((task)=>active(task) && task.status==='started');
    $('stats-next-step').innerHTML=next ? `<section class="next-step"><p class="eyebrow">Можно продолжить с этого</p><h3>${e(next.title)}</h3><p>${e(next.group || 'Без группы')}${next.minutes ? ` · около ${next.minutes} мин` : ''}</p><button class="action primary" data-stat-next="${next.id}">${next.status==='started' ? 'Продолжить' : 'Взять в работу'}</button></section>` : '<section class="next-step"><h3>Активных дел не осталось</h3><p>Можно выдохнуть или добавить новый план.</p><button class="action" data-page="assistant">Рассказать Мунину</button></section>';
    $('stats-details').hidden=!statsDetailsOpen; document.querySelector('[data-toggle-stat-details]').setAttribute('aria-expanded',String(statsDetailsOpen));
    const created=days.reduce((sum,day)=>sum+day.created,0), activeDays=days.filter((day)=>day.created||day.done).length;
    $('stats-details').innerHTML=`<p class="page-note">За те же 7 дней: добавлено ${created}, завершено ${done}, активных дней ${activeDays}. Активность включает создание и завершение.</p><table class="audit-table"><thead><tr><th>День</th><th>Добавлено</th><th>Готово</th></tr></thead><tbody>${days.map((day)=>`<tr><td>${dateLabel(day.date)}</td><td>${day.created}</td><td>${day.done}</td></tr>`).join('')}</tbody></table>`;
  }
  function identityForm(key) {
    if(binding?.key!==key) return '';
    const targetName=key==='email' ? 'почту' : key==='phone' ? 'телефон' : 'Telegram';
    if(binding.step==='remove') return `<div class="identity-form"><h3>Отключить ${targetName}?</h3><p>Данные сохранятся. Войти можно будет через оставшиеся способы.</p><div class="actions"><button class="action primary" data-confirm-unbind="${key}">Отключить</button><button class="action" data-cancel-bind>Отмена</button></div></div>`;
    if(binding.step==='telegram') return '<div class="identity-form"><p>В приложении здесь открывается вход через Telegram.</p><div class="actions"><button class="action primary" data-confirm-telegram>Подключить в примере</button><button class="action" data-cancel-bind>Отмена</button></div></div>';
    if(binding.step==='waiting') return `<div class="identity-form"><h3>Подтверждение звонком</h3><p>${e(binding.value)}</p><p>В приложении появится номер для звонка. После соединения можно сразу сбросить, вводить код не нужно.</p><p>В примере звонок заменён кнопкой:</p><div class="actions"><button class="action primary" data-demo-call-confirm>Подтвердить в примере</button><button class="action" data-cancel-bind>Отмена</button></div></div>`;
    if(binding.step==='merge') return `<div class="identity-form"><h3>На этой почте уже есть аккаунт</h3><p>3 задачи и 1 группа добавятся к твоим текущим данным. Почта станет ещё одним способом входа сюда.</p><div class="actions"><button class="action primary" data-confirm-merge>Перенести и подключить</button><button class="action" data-cancel-bind>Отмена</button></div></div>`;
    return `<form class="identity-form" id="bind-form">${binding.step==='code' ? `<h3>Код для ${e(binding.value)}</h3>${field('Код из письма','bind-value','text','inputmode="numeric" pattern="[0-9]{6}" maxlength="6" required autocomplete="one-time-code"')}<p>В примере: 123456.</p>` : field(key==='email' ? 'Адрес почты' : 'Номер телефона','bind-value',key==='email' ? 'email' : 'tel',`required ${key==='phone' ? 'pattern="[+0-9 ()-]{10,20}"' : ''} placeholder="${key==='email' ? 'name@example.test' : '+7 900 000-00-00'}"`)}<p class="field-error" id="bind-error" role="alert" hidden></p><div class="actions"><button class="action primary">${binding.step==='code' ? 'Подключить' : 'Продолжить'}</button><button class="action" type="button" data-cancel-bind>Отмена</button></div></form>`;
  }
  function renderIdentities() {
    const total=Object.values(identities).filter(Boolean).length;
    $('access-note').innerHTML=`${icon(total>=2 ? 'check' : 'plug')}<span>${total>=2 ? 'Запасной способ входа подключён.' : `${identities.telegram ? 'Telegram подключён' : identities.email ? 'Почта подключена' : 'Телефон подключён'}. Добавь запасной способ входа.`}</span>`;
    $('provider-list').innerHTML=[['telegram','Telegram','chat','Вход через Telegram'],['email','Почта','mail','Код в письме'],['phone','Телефон','phone','Подтверждение звонком']].map(([key,name,symbol,note])=>{const connected=identities[key],opened=binding?.key===key;return `<section class="identity-row"><div class="identity-summary">${icon(symbol)}<div><h2>${name}</h2><p class="${connected ? 'identity-state' : ''}">${connected ? e(connected) : note}</p></div>${opened ? '' : `<button class="action ${!connected && key==='email' && total===1 ? 'primary' : ''}" data-bind="${key}" aria-label="${connected ? 'Отключить' : 'Подключить'} ${key==='email' ? 'почту' : key==='phone' ? 'телефон' : 'Telegram'}" ${connected && total===1 ? 'disabled title="Единственный способ входа"' : ''}>${connected ? 'Отключить' : 'Подключить'}</button>`}</div>${identityForm(key)}</section>`;}).join('');
    $('identity-protection').textContent=total===1 ? 'Единственный способ входа нельзя отключить. Сначала подключи ещё один.' : '';
    $('identity-help-text').innerHTML=`При подключении почты или Telegram можно перенести задачи из прежнего аккаунта. Перенос требует отдельного подтверждения.${!identities.email ? '<br><button data-merge-example>Посмотреть пример переноса</button>' : ''}`;
    syncNotificationAvailability();
  }
  function syncNotificationAvailability() {
    const master = $('notify-master').checked;
    $('notify-telegram').disabled = !master || !identities.telegram;
    $('notify-email').disabled = !master || !identities.email;
    $('notify-push').disabled = true; $('notify-urgent').disabled = !master; $('reminder-lead').disabled = !master;
  }
  function refreshPages() { renderList(); renderGroups(); renderStats(); }
  const originalRender = render, originalShowPage = showPage, originalReset = reset;
  render = (revealNext = false) => { observeTasks(); originalRender(revealNext); refreshPages(); };
  showPage = (page) => {
    if (!labels[page]) return; closeQuickMenu(false); finishFolderDrag();
    originalShowPage(page);
    document.querySelectorAll('.workspace > section.content').forEach((element) => { element.hidden = element.id !== `${page}-page`; });
    $('top-label').textContent = labels[page];
    document.querySelector('.bottom-nav [data-page="more"]')?.toggleAttribute('aria-current', ['more','stats','settings','integrations'].includes(page));
    const moreButton = document.querySelector('.bottom-nav [data-page="more"]');
    if (moreButton?.hasAttribute('aria-current')) moreButton.setAttribute('aria-current', 'page');
  };
  function newTask(group = '') { showPage('all'); $('new-task-form').hidden = false; $('new-task-group').innerHTML = groupOptions(); $('new-task-group').value = group; $('new-task-title').focus(); }
  function openGroup(name = null) {
    editingGroup = name; const group = groups.find((item) => item.name === name);
    $('group-form-heading').textContent = name ? 'Изменить группу' : 'Новая группа'; $('group-name').value = name || '';
    $('group-symbol').value = group?.symbol || 'groups'; groupColor = group?.color || colors[0];
    $('group-error').hidden = true; $('group-form').hidden = false; syncGroupColors(); $('group-name').focus();
  }
  function syncGroupColors() { document.querySelectorAll('[data-group-color]').forEach((button) => button.setAttribute('aria-pressed', String(button.dataset.groupColor === groupColor))); }
  function chooseTheme(theme) {
    settings.theme = theme;
    const dark = theme === 'dark' || theme === 'system' && matchMedia('(prefers-color-scheme: dark)').matches;
    document.documentElement.dataset.theme = dark ? 'dark' : 'light';
    $('theme-label').textContent = dark ? 'Ночь' : 'День'; $('theme').setAttribute('aria-label', dark ? 'Переключить на светлую тему' : 'Переключить на тёмную тему');
    document.querySelectorAll('[data-theme-choice]').forEach((button) => button.setAttribute('aria-pressed', String(button.dataset.themeChoice === theme)));
  }
  document.addEventListener('click', (event) => {
    const button = event.target.closest('button'); if (!button?.closest('.secondary-page')) return;
    const data = button.dataset;
    if (data.page) showPage(data.page);
    if (data.openTask) openTaskEditor(Number(data.openTask));
    if (data.toggleTask) { const task = tasks.find((item) => item.id === Number(data.toggleTask)); setTaskStatus(task, ['done', 'cancelled'].includes(task.status) ? 'todo' : 'done'); render(); notice(task.status === 'done' ? 'Готово. Задача завершена.' : 'Задача возвращена в список.'); const next = document.querySelector(`#${document.body.dataset.page}-page [data-toggle-task="${task.id}"]`); (next || $('task-search'))?.focus(); }
    if ('newTask' in data) newTask(data.taskGroup);
    if ('closeNew' in data) { $('new-task-form').hidden = true; document.querySelector('#all-page [data-new-task]').focus(); }
    if (data.period) { period = data.period; listPage = 1; document.querySelectorAll('[data-period]').forEach((item) => item.setAttribute('aria-pressed', String(item.dataset.period === period))); renderList(); }
    if ('clearFilters' in data) { period = 'all'; search = ''; groupFilter = ''; statusFilter = ''; $('task-search').value = ''; $('list-status').value = ''; document.querySelectorAll('[data-period]').forEach((item) => item.setAttribute('aria-pressed', String(item.dataset.period === 'all'))); renderList(); $('task-search').focus(); }
    if (data.lane) { lane = data.lane; renderBoard(); document.querySelector(`[data-lane="${lane}"]`).focus(); }
    if ('newGroup' in data) openGroup();
    if (data.editGroup) openGroup(data.editGroup);
    if ('closeGroup' in data) { $('group-form').hidden = true; document.querySelector('#groups-page [data-new-group]').focus(); }
    if (data.expandGroup) { expandedGroup = expandedGroup === data.expandGroup ? null : data.expandGroup; renderGroups(); [...document.querySelectorAll('[data-expand-group]')].find((item) => item.dataset.expandGroup === data.expandGroup)?.focus(); }
    if (data.groupColor) { groupColor = data.groupColor; syncGroupColors(); }
    if (data.chartDay) { selectedDay = Number(data.chartDay); receiptDay = selectedDay; renderStats(); document.querySelector(`[data-chart-day="${selectedDay}"]`).focus(); }
    if ('showUrgent' in data) { period = 'now'; search = ''; groupFilter = ''; statusFilter = ''; $('task-search').value = ''; $('list-status').value = ''; document.querySelectorAll('[data-period]').forEach((item) => item.setAttribute('aria-pressed', String(item.dataset.period === period))); renderList(); showPage('all'); }
    if (data.themeChoice) chooseTheme(data.themeChoice);
    if ('cleanup' in data) { $('cleanup-confirm').hidden = false; document.querySelector('[data-confirm-cleanup]').focus(); }
    if ('cancelCleanup' in data) { $('cleanup-confirm').hidden = true; document.querySelector('[data-cleanup]').focus(); }
    if ('confirmCleanup' in data) { tasks.filter((task) => ['done','cancelled'].includes(task.status)).forEach((task) => { task.archived = true; }); render(); $('cleanup-confirm').hidden = true; document.querySelector('[data-cleanup]').focus(); notice('Завершённые и отменённые задачи убраны из списков.'); }
    if (data.bind) { binding = {key: data.bind, step: identities[data.bind] ? 'remove' : data.bind === 'telegram' ? 'telegram' : 'address'}; renderIdentities(); ($('bind-value') || document.querySelector('[data-confirm-unbind], [data-confirm-telegram]'))?.focus(); }
    if ('confirmTelegram' in data) { identities.telegram = '@sasha'; binding = null; renderIdentities(); notice('Telegram подключён в примере.'); }
    if ('cancelBind' in data) { const wasMerge = binding?.step === 'merge'; binding = null; renderIdentities(); if (wasMerge) notice('Перенос отменён. Способ входа не подключён.'); }
    if (data.confirmUnbind) { if (Object.values(identities).filter(Boolean).length < 2) return; identities[data.confirmUnbind] = ''; binding = null; renderIdentities(); notice('Способ входа отключён в примере.'); }
  });
  $('task-search').addEventListener('input', (event) => { search = event.target.value; listPage = 1; renderList(); });
  $('list-group').addEventListener('change', (event) => { groupFilter = event.target.value; listPage = 1; renderList(); });
  $('list-status').addEventListener('change', (event) => { statusFilter = event.target.value; listPage = 1; renderList(); });
  $('new-task-form').addEventListener('submit', (event) => {
    event.preventDefault(); const title = $('new-task-title').value.trim(); if (!title) return;
    tasks.push({id: nextTaskId++, title, group: $('new-task-group').value, status: 'todo', minutes: null, meta: 'Без срока', createdDay: 6});
    search = ''; groupFilter = ''; statusFilter = ''; period = 'all'; listPage = 1; $('task-search').value = ''; $('list-status').value = ''; $('new-task-title').value = ''; $('new-task-form').hidden = true;
    document.querySelectorAll('[data-period]').forEach((item) => item.setAttribute('aria-pressed', String(item.dataset.period === 'all'))); render(); notice('Задача добавлена.'); document.querySelector('#all-page [data-new-task]').focus();
  });
  $('group-form').addEventListener('submit', (event) => {
    event.preventDefault(); const name = $('group-name').value.trim(); if (!name) return;
    if (groups.some((group) => group.name.toLocaleLowerCase('ru') === name.toLocaleLowerCase('ru') && group.name !== editingGroup)) { $('group-error').textContent = 'Группа с таким названием уже есть.'; $('group-error').hidden = false; return; }
    if (editingGroup) { const group = groups.find((item) => item.name === editingGroup); tasks.filter((task) => task.group === editingGroup).forEach((task) => { task.group = name; }); if (groupFilter === editingGroup) groupFilter = name; if (expandedGroup === editingGroup) expandedGroup = name; Object.assign(group, {name, color: groupColor, symbol: $('group-symbol').value}); }
    else groups.push({name, color: groupColor, symbol: $('group-symbol').value});
    folderSearch = ''; $('folder-search').value = ''; folderLimit = Math.max(folderLimit, groups.length); expandedGroup = name; folderOpened = true;
    $('group-form').hidden = true; render(); notice('Группа сохранена.'); document.querySelector('#groups-page [data-new-group]').focus();
  });
  $('board-grid').addEventListener('change', (event) => { const select = event.target.closest('[data-task-status]'); if (!select) return; const id = Number(select.dataset.taskStatus); setTaskStatus(tasks.find((task) => task.id === id), select.value); render(); const next = document.querySelector(`[data-task-status="${id}"]`); (next?.getClientRects().length ? next : document.querySelector(`[data-lane="${lane}"]`))?.focus(); notice('Статус изменён.'); });
  let draggedTask = null;
  $('board-grid').addEventListener('dragstart', (event) => { const card = event.target.closest('[data-drag-task]'); if (!card || innerWidth < 1024) { event.preventDefault(); return; } draggedTask = Number(card.dataset.dragTask); event.dataTransfer.effectAllowed = 'move'; event.dataTransfer.setData('text/plain', String(draggedTask)); });
  $('board-grid').addEventListener('dragover', (event) => { if (draggedTask === null) return; const target = event.target.closest('[data-drop-status]'); if (target) { event.preventDefault(); document.querySelectorAll('.is-drop-target').forEach((item) => item.classList.remove('is-drop-target')); target.classList.add('is-drop-target'); } });
  $('board-grid').addEventListener('drop', (event) => { const target = event.target.closest('[data-drop-status]'); if (!target || draggedTask === null) return; event.preventDefault(); setTaskStatus(tasks.find((task) => task.id === draggedTask), target.dataset.dropStatus); draggedTask = null; render(); notice('Задача перенесена.'); });
  $('board-grid').addEventListener('dragend', () => { draggedTask = null; document.querySelectorAll('.is-drop-target').forEach((item) => item.classList.remove('is-drop-target')); });
  $('profile-form').addEventListener('submit', (event) => { event.preventDefault(); const name = $('profile-name').value.trim(); if (!name) return; settings.name = name; document.querySelector('.lead-name').textContent = `Привет, ${name}.`; document.querySelector('.avatar').textContent = name.slice(0,1).toUpperCase(); $('sidebar-name').textContent = name; $('profile-status').textContent = 'Имя сохранено в примере.'; });
  $('timezone').addEventListener('change', (event) => { settings.timezone = event.target.value; notice('Часовой пояс выбран в примере. Даты здесь демонстрационные.'); });
  $('notify-master').addEventListener('change', syncNotificationAvailability);
  $('theme').addEventListener('click', () => chooseTheme(document.documentElement.dataset.theme));
  matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => { if (settings.theme === 'system') chooseTheme('system'); });
  $('provider-list').addEventListener('submit', (event) => { if (event.target.id !== 'bind-form') return; event.preventDefault(); const value = $('bind-value').value.trim(); if (binding.step === 'address') { binding.value = value; binding.step = binding.key === 'phone' ? 'waiting' : 'code'; renderIdentities(); $('bind-value')?.focus(); } else if (value !== '123456') { $('bind-error').textContent = 'В этом примере код — 123456.'; $('bind-error').hidden = false; } else if (binding.value === 'merge@example.test') { binding.step = 'merge'; renderIdentities(); } else { identities[binding.key] = binding.value; binding = null; renderIdentities(); notice('Способ входа подключён в примере.'); } });

  function offerUndo(message, restore) {
    notice(message); clearTimeout(toastTimer);
    const button=document.createElement('button'); button.type='button'; button.textContent='Отменить';
    button.addEventListener('click',()=>{restore();render();notice('Изменение отменено.');}); $('toast').append(button);
    toastTimer=setTimeout(()=>{$('toast').hidden=true;},8000);
  }
  function changeTasks(items, apply, message) {
    const snapshots=items.map((task)=>({id:task.id,values:{...task}})), beforeCounts={...counts}, beforeCompleted=[...completedToday];
    items.forEach(apply); render();
    offerUndo(message,()=>{snapshots.forEach(({id,values})=>{const task=tasks.find((item)=>item.id===id);if(!task)return;Object.keys(task).forEach((key)=>{if(!(key in values))delete task[key];});Object.assign(task,values);knownTasks.set(id,{status:task.status,title:task.title});});counts=beforeCounts;completedToday=beforeCompleted;});
  }
  function animateFolder(element=$('group-canvas')) {
    if(!motionPreference.matches) element.animate([{opacity:0,transform:'translateY(7px)'},{opacity:1,transform:'translateY(0)'}],{duration:180,easing:'cubic-bezier(.16,1,.3,1)'});
  }
  function openFolder(name) {
    expandedGroup=name;folderOpened=true;folderPage=1;folderHistory=false;renderGroups();animateFolder();
    $('group-view-title')?.focus({preventScroll:true});if(innerWidth<1024)window.scrollTo({top:0,behavior:'instant'});
  }
  function closeQuickMenu(restoreFocus=true) {
    $('quick-menu').hidden=true; menuOpener?.setAttribute('aria-expanded','false');
    if(restoreFocus && menuOpener?.isConnected) menuOpener.focus({preventScroll:true});
    menuState=null;menuOpener=null;
  }
  function menuItem(action,label,symbol='arrow',extra='') { return `<button role="menuitem" data-quick-action="${action}" ${extra}>${icon(symbol)}${label}</button>`; }
  function paintQuickMenu() {
    if(!menuState) return;
    const task=menuState.kind==='task' ? tasks.find((item)=>item.id===menuState.id) : null;
    const title=task?.title || (menuState.kind==='group' ? menuState.name : `Выбрано ${selectedIds.size}`);
    let content='';
    if(menuState.step==='move') {
      content=`${menuItem('back','Назад','arrow')}<input class="field-control menu-search" id="quick-group-search" type="search" aria-label="Найти группу для переноса" placeholder="Найти группу"><div class="quick-group-list" id="quick-group-list"></div>`;
    } else if(menuState.step==='delete-group') {
      content=`<p class="menu-note">Задачи останутся в списке без группы.</p>${menuItem('confirm-delete-group','Удалить группу','groups','class="menu-danger"')}${menuItem('back','Оставить группу','arrow')}`;
    } else if(menuState.kind==='group') {
      content=`${menuItem('open-group','Открыть группу','groups')}${menuItem('new-group-task','Добавить задачу','plus')}${menuItem('edit-group','Название, значок и цвет','edit')}<hr>${menuItem('delete-group','Удалить группу','groups','class="menu-danger"')}`;
    } else if(task) {
      const finished=['done','cancelled'].includes(task.status);
      content=`${menuItem('edit','Открыть детали','edit')}${menuItem('complete',finished ? 'Вернуть к выполнению' : 'Завершить','check')}${!finished ? menuItem('working',task.status==='started' ? 'Снять «В работе»' : 'Взять в работу','play')+menuItem('tomorrow','Отложить на завтра','clock') : ''}${menuItem('move','Переместить в группу','groups')}${!finished ? menuItem('cancel','Отменить задачу','cancel','class="menu-danger"') : ''}${finished ? `<hr>${menuItem('archive','Убрать из списка','archive')}` : ''}`;
    }
    $('quick-menu').innerHTML=`<header><strong>${e(title)}</strong><small>${menuState.kind==='group' ? 'Управление группой' : 'Быстрые действия'}</small></header>${content}`;
    if(menuState.step==='move') paintMenuGroups('');
  }
  function paintMenuGroups(query) {
    const task=menuState?.kind==='task' ? tasks.find((item)=>item.id===menuState.id) : null;
    const names=['',...groups.map((group)=>group.name)].filter((name)=>(name || 'Без группы').toLocaleLowerCase('ru').includes(query.toLocaleLowerCase('ru')));
    $('quick-group-list').innerHTML=names.length ? names.map((name)=>`<button role="menuitem" data-quick-group="${e(name)}" ${task?.group===name ? 'disabled' : ''}>${name ? dot(name) : icon('groups')}${e(name || 'Без группы')}${task?.group===name ? ' · сейчас здесь' : ''}</button>`).join('') : '<p class="menu-note">Группа не найдена.</p>';
  }
  function placeQuickMenu(x,y) {
    const menu=$('quick-menu'), rect=menu.getBoundingClientRect(), bottom=innerWidth<1024 ? 82 : 8;
    menu.style.left=`${Math.max(8,Math.min(x,document.documentElement.clientWidth-rect.width-8))}px`;
    menu.style.top=`${Math.max(8,Math.min(y,innerHeight-rect.height-bottom))}px`;
  }
  function openQuickMenu(state,x,y,opener) {
    closeQuickMenu(false);menuState={...state,step:'main'};menuOpener=opener;opener?.setAttribute('aria-expanded','true');
    $('quick-menu').hidden=false;paintQuickMenu();placeQuickMenu(x,y);$('quick-menu').querySelector('[role="menuitem"]:not(:disabled)')?.focus({preventScroll:true});
  }
  function focusMenuStep() {
    const rect=$('quick-menu').getBoundingClientRect();paintQuickMenu();placeQuickMenu(rect.x,rect.y);
    ($('quick-group-search') || $('quick-menu').querySelector('[role="menuitem"]:not(:disabled)'))?.focus({preventScroll:true});
  }
  function performQuickAction(action) {
    const state=menuState;if(!state)return;
    if(action==='move' || action==='delete-group' || action==='back') {menuState.step=action==='move' ? 'move' : action==='delete-group' ? 'delete-group' : 'main';focusMenuStep();return;}
    const task=state.kind==='task' ? tasks.find((item)=>item.id===state.id) : null;
    closeQuickMenu(false);
    if(action==='edit' && task) {openTaskEditor(task.id);return;}
    if(state.kind==='group') {
      if(action==='open-group') openFolder(state.name);
      if(action==='new-group-task') newTask(state.name);
      if(action==='edit-group') {openGroup(state.name);$('group-form').scrollIntoView({block:'nearest'});}
      if(action==='confirm-delete-group') {
        const index=groups.findIndex((group)=>group.name===state.name), group=groups[index], affected=tasks.filter((item)=>item.group===state.name);
        groups.splice(index,1);affected.forEach((item)=>{item.group='';});if(groupFilter===state.name)groupFilter='';render();
        offerUndo('Группа удалена. Задачи остались без группы.',()=>{groups.splice(index,0,group);affected.forEach((item)=>{item.group=group.name;});expandedGroup=group.name;});
      }
      return;
    }
    if(!task)return;
    if(action==='complete') changeTasks([task],(item)=>setTaskStatus(item,['done','cancelled'].includes(item.status) ? 'todo' : 'done'),['done','cancelled'].includes(task.status) ? 'Задача возвращена к выполнению.' : 'Готово. Задача завершена.');
    if(action==='working') changeTasks([task],(item)=>setTaskStatus(item,item.status==='started' ? 'todo' : 'started'),task.status==='started' ? 'Статус «В работе» снят.' : 'Задача взята в работу.');
    if(action==='tomorrow') changeTasks([task],(item)=>{setTaskStatus(item,'moved');item.plannedDate='2026-10-06';item.meta=taskMeta(item);counts.moved++;},'День исполнения перенесён на завтра.');
    if(action==='cancel') changeTasks([task],(item)=>setTaskStatus(item,'cancelled'),'Задача отменена.');
    if(action==='archive') changeTasks([task],(item)=>{item.archived=true;},'Задача убрана из списка.');
    document.querySelector(`#${document.body.dataset.page}-page [data-record-id="${task.id}"] .record-open`)?.focus({preventScroll:true});
  }
  document.addEventListener('contextmenu',(event)=>{
    const record=event.target.closest('.list-record[data-record-id], .folder-task[data-record-id]'), folder=event.target.closest('[data-open-folder]');
    if(!record && !folder)return;event.preventDefault();
    openQuickMenu(record ? {kind:'task',id:Number(record.dataset.recordId)} : {kind:'group',name:folder.dataset.openFolder},event.clientX,event.clientY,record?.querySelector('.record-menu') || folder);
  });
  document.addEventListener('pointerdown',(event)=>{if(!$('quick-menu').hidden && !$('quick-menu').contains(event.target) && !menuOpener?.contains(event.target))closeQuickMenu(false);});
  document.addEventListener('keydown',(event)=>{
    if(event.key==='Escape' && folderPointer){event.preventDefault();finishFolderDrag();renderGroups();return;}
    if((event.shiftKey && event.key==='F10') || event.key==='ContextMenu') {
      const record=document.activeElement?.closest('[data-record-id]'), folder=document.activeElement?.closest('[data-open-folder]');if(!record&&!folder)return;
      event.preventDefault();const rect=document.activeElement.getBoundingClientRect();openQuickMenu(record ? {kind:'task',id:Number(record.dataset.recordId)} : {kind:'group',name:folder.dataset.openFolder},rect.x+24,rect.bottom,document.activeElement);return;
    }
    if($('quick-menu').hidden)return;
    if(event.key==='Escape') {event.preventDefault();closeQuickMenu();return;}
    if(event.key==='Tab') {closeQuickMenu(false);return;}
    if(!['ArrowDown','ArrowUp','Home','End'].includes(event.key))return;
    if(event.target.matches('input') && event.key!=='ArrowDown')return;
    event.preventDefault();const buttons=[...$('quick-menu').querySelectorAll('[role="menuitem"]:not(:disabled)')],index=buttons.indexOf(document.activeElement);
    const next=event.key==='Home' ? 0 : event.key==='End' ? buttons.length-1 : event.key==='ArrowDown' ? (index+1)%buttons.length : (index-1+buttons.length)%buttons.length;
    buttons[next]?.focus();
  });
  $('quick-menu').addEventListener('input',(event)=>{if(event.target.id==='quick-group-search')paintMenuGroups(event.target.value);});
  $('folder-search').addEventListener('input',(event)=>{folderSearch=event.target.value;renderGroups();});
  $('list-order').addEventListener('change',()=>{listPage=1;renderList();});
  $('goal-editor').addEventListener('submit',(event)=>{event.preventDefault();weeklyGoal=Number($('goal-value').value);goalEditorOpen=false;renderStats();document.querySelector('[data-edit-goal]').focus();notice('Ориентир недели изменён в примере.');});
  document.addEventListener('click',(event)=>{
    const button=event.target.closest('button');if(!button)return;const data=button.dataset;
    if(data.quickAction) {performQuickAction(data.quickAction);return;}
    if('quickGroup' in data && menuState) {
      const name=data.quickGroup,items=menuState.kind==='bulk' ? tasks.filter((task)=>selectedIds.has(task.id)) : [tasks.find((task)=>task.id===menuState.id)];closeQuickMenu(false);
      changeTasks(items.filter(Boolean),(task)=>{task.group=name;},`Перенесено ${items.length} ${doneWord(items.length)}: ${name || 'Без группы'}.`);selecting=false;selectedIds.clear();renderList();return;
    }
    if(data.taskMenu || data.groupMenu || 'bulkMove' in data) {const rect=button.getBoundingClientRect();openQuickMenu(data.taskMenu ? {kind:'task',id:Number(data.taskMenu)} : data.groupMenu ? {kind:'group',name:data.groupMenu} : {kind:'bulk'},rect.right-272,rect.bottom+4,button);if('bulkMove' in data){menuState.step='move';focusMenuStep();}return;}
    if(data.completeRecord) {
      const task=tasks.find((item)=>item.id===Number(data.completeRecord));if(!task)return;
      if(selecting && button.closest('#all-page')) {selectedIds.has(task.id) ? selectedIds.delete(task.id) : selectedIds.add(task.id);renderList();document.querySelector(`[data-complete-record="${task.id}"]`)?.focus({preventScroll:true});}
      else {const finished=['done','cancelled'].includes(task.status);changeTasks([task],(item)=>setTaskStatus(item,finished ? 'todo' : 'done'),finished ? 'Задача возвращена к выполнению.' : 'Готово. Задача завершена.');(document.querySelector(`#${document.body.dataset.page}-page [data-complete-record="${task.id}"]`) || document.querySelector(`#${document.body.dataset.page}-page .completion-hit`))?.focus({preventScroll:true});}return;
    }
    if('toggleFilters' in data) {$('list-filters').hidden=!$('list-filters').hidden;button.setAttribute('aria-expanded',String(!$('list-filters').hidden));}
    if('toggleSelection' in data) {selecting=!selecting;selectedIds.clear();renderList();document.querySelector('.selection-button').focus({preventScroll:true});}
    if('selectPage' in data) {const all=visibleRecords.every((task)=>selectedIds.has(task.id));visibleRecords.forEach((task)=>all ? selectedIds.delete(task.id) : selectedIds.add(task.id));renderList();}
    if('bulkDone' in data) {const items=tasks.filter((task)=>selectedIds.has(task.id));changeTasks(items,(task)=>setTaskStatus(task,'done'),`Завершено ${items.length} ${doneWord(items.length)}.`);selecting=false;selectedIds.clear();renderList();}
    if('toggleHistory' in data) {historyOpen=!historyOpen;listPage=1;renderList();document.querySelector('[data-toggle-history]')?.focus({preventScroll:true});}
    if(data.listPage) {listPage=Number(data.listPage);renderList();$('all-list').scrollIntoView({block:'start'});$('task-search').focus({preventScroll:true});}
    if('clearFilters' in data) {listPage=1;renderList();}
    if(data.openFolder) openFolder(data.openFolder);
    if('folderBack' in data) {folderOpened=false;renderGroups();$('folder-search').focus({preventScroll:true});window.scrollTo({top:0,behavior:'instant'});}
    if('folderHistory' in data) {folderHistory=!folderHistory;folderPage=1;renderGroups();}
    if(data.folderPage) {folderPage=Number(data.folderPage);renderGroups();$('group-view-title')?.focus({preventScroll:true});}
    if('moreFolders' in data) {folderLimit+=12;renderGroups();}
    if('editGoal' in data) {goalEditorOpen=!goalEditorOpen;renderStats();if(goalEditorOpen)$('goal-value').focus();}
    if('closeGoal' in data) {goalEditorOpen=false;renderStats();document.querySelector('[data-edit-goal]').focus();}
    if('allReceipts' in data) {receiptDay=null;renderStats();document.querySelector('[data-all-receipts]').focus({preventScroll:true});}
    if('toggleStatDetails' in data) {statsDetailsOpen=!statsDetailsOpen;renderStats();}
    if(data.statGroup) {showPage('groups');openFolder(data.statGroup);}
    if(data.statNext) {const task=tasks.find((item)=>item.id===Number(data.statNext));if(task.status!=='started') {setTaskStatus(task,'started');render();}showPage('focus');notice(`В работе: «${task.title}».`);}
    if('toggleIdentityHelp' in data) {$('identity-help-text').hidden=!$('identity-help-text').hidden;button.setAttribute('aria-expanded',String(!$('identity-help-text').hidden));}
    if('demoCallConfirm' in data && binding) {identities.phone=binding.value;binding=null;renderIdentities();notice('Телефон подключён в примере.');}
    if('mergeExample' in data) {binding={key:'email',step:'merge',value:'merge@example.test'};renderIdentities();document.querySelector('[data-confirm-merge]')?.focus();}
    if('confirmMerge' in data && binding?.step==='merge') {const name='Из другого аккаунта';if(!groups.some((group)=>group.name===name))groups.push({name,color:colors[7],symbol:'groups'});['Забрать книгу','Проверить список покупок','Разобрать старые заметки'].forEach((title)=>tasks.push({id:nextTaskId++,title,group:name,status:'todo',minutes:null,meta:'Без срока',createdDay:-8}));identities[binding.key]=binding.value;binding=null;renderIdentities();render();notice('Перенесено 3 задачи и 1 группа. Почта подключена.');}
  });
  function finishFolderDrag() {
    if(folderPointer && $('group-list').hasPointerCapture(folderPointer.id))$('group-list').releasePointerCapture(folderPointer.id);folderPointer=null;
    clearTimeout(folderHoverTimer);folderHoverTimer=null;folderDragId=null;folderHoverName=null;$('folder-drag-ghost')?.remove();
    $('group-list').classList.remove('is-dragging');$('folder-hover-preview').hidden=true;$('folder-drag-rest').hidden=true;
    document.querySelectorAll('.folder-tile.is-drag-target').forEach((tile)=>tile.classList.remove('is-drag-target'));
    const stage=document.querySelector('.folder-stage');if(stage && expandedGroup)stage.dataset.dropFolder=expandedGroup;
  }
  let folderPointer=null;
  function beginFolderDrag(id) {
    closeQuickMenu(false);folderDragId=id;
    const task=tasks.find((item)=>item.id===id),group=groups.find((item)=>item.name===task.group);
    $('folder-drag-rest').innerHTML=`${folderEmblem(group)}<span>${e(group?.name || 'Группа')} закрыта</span><small>Наведи задачу на другую папку</small>`;
    $('folder-drag-rest').hidden=false;$('group-list').classList.add('is-dragging');
    document.querySelectorAll('[data-open-folder]').forEach((tile)=>tile.setAttribute('aria-expanded','false'));
    const ghost=document.createElement('div');ghost.id='folder-drag-ghost';ghost.className='folder-drag-ghost';ghost.textContent=task.title;document.body.append(ghost);
  }
  function hoverFolder(name) {
    const group=groups.find((item)=>item.name===name);if(folderHoverName===name)return;
    folderHoverName=name;clearTimeout(folderHoverTimer);$('folder-hover-preview').hidden=true;
    document.querySelectorAll('[data-open-folder]').forEach((tile)=>{tile.classList.toggle('is-drag-target',tile.dataset.openFolder===name);tile.setAttribute('aria-expanded','false');});
    if(!group)return;
    folderHoverTimer=setTimeout(()=>{if(folderDragId===null || folderHoverName!==name)return;$('folder-hover-preview').innerHTML=folderCanvas(group,true);$('folder-hover-preview').hidden=false;document.querySelector('.folder-stage').dataset.dropFolder=name;document.querySelectorAll('[data-open-folder]').forEach((tile)=>tile.setAttribute('aria-expanded',String(tile.dataset.openFolder===name)));animateFolder($('folder-hover-preview'));},400);
  }
  $('group-list').addEventListener('pointerdown',(event)=>{
    const card=event.target.closest('[data-group-task]');if(!card || innerWidth<1024 || event.button!==0 || event.target.closest('button'))return;
    folderPointer={id:event.pointerId,taskId:Number(card.dataset.groupTask),x:event.clientX,y:event.clientY};
    $('group-list').setPointerCapture(event.pointerId);event.preventDefault();
  });
  $('group-list').addEventListener('pointermove',(event)=>{
    if(!folderPointer || event.pointerId!==folderPointer.id)return;
    if(folderDragId===null && Math.hypot(event.clientX-folderPointer.x,event.clientY-folderPointer.y)<6)return;
    if(folderDragId===null)beginFolderDrag(folderPointer.taskId);
    const ghost=$('folder-drag-ghost');ghost.style.left=`${Math.max(8,Math.min(event.clientX+14,innerWidth-250))}px`;ghost.style.top=`${Math.min(event.clientY+14,innerHeight-60)}px`;
    const target=document.elementFromPoint(event.clientX,event.clientY)?.closest('[data-drop-folder]');hoverFolder(target?.dataset.dropFolder || null);
  });
  function endFolderPointer(event,cancelled=false) {
    if(!folderPointer || event.pointerId!==folderPointer.id)return;
    const target=cancelled ? null : document.elementFromPoint(event.clientX,event.clientY)?.closest('[data-drop-folder]'),name=target?.dataset.dropFolder,task=tasks.find((item)=>item.id===folderDragId);
    if($('group-list').hasPointerCapture(event.pointerId))$('group-list').releasePointerCapture(event.pointerId);
    folderPointer=null;finishFolderDrag();
    if(!task || !groups.some((group)=>group.name===name)){renderGroups();return;}
    expandedGroup=name;folderOpened=true;folderPage=1;folderHistory=false;
    if(task.group!==name)changeTasks([task],(item)=>{item.group=name;},`Задача перенесена: ${name}.`);else renderGroups();animateFolder();
  }
  $('group-list').addEventListener('pointerup',(event)=>endFolderPointer(event));
  $('group-list').addEventListener('pointercancel',(event)=>endFolderPointer(event,true));
  $('large-example').addEventListener('click',()=>{
    if(denseExample)return;denseExample=true;
    const extraNames=['Дом','Здоровье','Покупки','Чтение','Поездка','Финансы','Спорт','Документы','Курсы','Портфолио','Встречи','Когда-нибудь'];
    for(const name of extraNames){if(groups.length>=16)break;if(!groups.some((group)=>group.name===name))groups.push({name,color:colors[groups.length%colors.length],symbol:'groups'});}
    const titles=['Проверить список материалов','Записать вопросы к встрече','Уточнить дату доставки','Собрать документы','Прочитать следующую главу','Проверить правки','Записаться на консультацию','Обновить список расходов','Отправить письмо','Подготовить короткий план','Разобрать записи','Посмотреть варианты','Согласовать время','Забрать заказ','Сделать черновик'];
    let index=0;while(tasks.length<120){const done=index%13===0;tasks.push({id:nextTaskId++,title:`${titles[index%titles.length]} ${Math.floor(index/titles.length)+1}`,group:index<22 ? groups[0].name : groups[index%groups.length].name,status:done ? 'done' : index%17===0 ? 'started' : 'todo',minutes:[15,30,null][index%3],createdDay:-8,...(done ? {completedDay:-4} : {})});index++;}
    tasks.forEach((task)=>{task.meta=taskMeta(task);knownTasks.set(task.id,{status:task.status,title:task.title});});$('large-example').textContent='120 задач загружено';$('large-example').disabled=true;listPage=1;render();notice('В примере теперь 120 задач и 16 групп.');
  });

  reset = () => {
    knownTasks.clear(); groups = [{name:'Учёба',color:colors[0],symbol:'groups'},{name:'Личное',color:colors[2],symbol:'focus'},{name:'Работа',color:colors[3],symbol:'list'},{name:'Идеи',color:colors[1],symbol:'groups'}];
    period = 'all'; search = ''; groupFilter = ''; statusFilter = ''; expandedGroup = null;
    listPage = 1; selecting = false; selectedIds.clear(); historyOpen = false; folderSearch = ''; folderOpened = false; folderPage = 1; folderHistory = false; folderLimit = 12; weeklyGoal = 5; receiptDay = null; goalEditorOpen = false; statsDetailsOpen = false; denseExample = false; closeQuickMenu(false); finishFolderDrag(); lane = 'todo'; selectedDay = 6; binding = null; identities = {telegram:'@sasha',email:'',phone:''};
    originalReset();
    Object.assign(tasks[0], {deadline:'2026-10-05T18:00',priority:'normal',createdDay:2});
    Object.assign(tasks[1], {plannedDate:'2026-10-05',priority:'urgent',createdDay:4});
    Object.assign(tasks[2], {createdDay:5});
    tasks.push(
      {id:4,title:'Проверить правки к макету',group:'Работа',minutes:20,status:'todo',deadline:'2026-10-04T17:00',createdDay:3},
      {id:5,title:'Подготовиться к встрече с руководителем',group:'Учёба',minutes:45,status:'todo',deadline:'2026-10-07T12:00',plannedDate:'2026-10-06',createdDay:5},
      {id:6,title:'Купить продукты на неделю',group:'Личное',minutes:30,status:'todo',createdDay:6},
      {id:7,title:'Набросать идею для портфолио',group:'Идеи',minutes:null,status:'todo',createdDay:6},
      {id:8,title:'Отправить макет',group:'Работа',minutes:15,status:'done',createdDay:0,completedDay:6},
      {id:9,title:'Позвонить Саше',group:'Личное',minutes:10,status:'done',createdDay:2,completedDay:6},
      {id:10,title:'Собрать материалы для диплома',group:'Учёба',minutes:30,status:'done',createdDay:0,completedDay:3},
      {id:11,title:'Оплатить интернет',group:'Личное',minutes:5,status:'done',createdDay:1,completedDay:4},
      {id:12,title:'Обновить резюме',group:'Работа',minutes:30,status:'started',createdDay:1},
      {id:13,title:'Заказать старый учебник',group:'Учёба',minutes:10,status:'cancelled',createdDay:2}
    );
    $('folder-search').value = ''; $('list-filters').hidden = true; document.querySelector('[data-toggle-filters]').setAttribute('aria-expanded', 'false'); $('list-order').value = 'default'; $('large-example').textContent = '120 задач и 16 групп'; $('large-example').disabled = false;
    tasks.forEach((task) => { task.meta = taskMeta(task); knownTasks.set(task.id, {status:task.status,title:task.title}); }); nextTaskId = 14;
    $('task-search').value = ''; $('list-status').value = ''; $('new-task-form').hidden = true; $('group-form').hidden = true; $('cleanup-confirm').hidden = true;
    document.querySelectorAll('[data-period]').forEach((button) => button.setAttribute('aria-pressed', String(button.dataset.period === 'all')));
    document.querySelectorAll('#settings-page form').forEach((form) => form.reset());
    $('timezone').value = 'Europe/Moscow'; settings.name = 'Саша'; $('sidebar-name').textContent = 'Саша'; settings.timezone = 'Europe/Moscow'; $('profile-status').textContent = '';
    document.querySelector('.lead-name').textContent = 'Привет, Саша.'; document.querySelector('.avatar').textContent = 'С';
    $('notify-master').checked = true; $('notify-telegram').checked = true; $('notify-email').checked = false; $('notify-urgent').checked = false;
    $('reminder-lead').selectedIndex = 1; $('voice-desktop').selectedIndex = 0; $('voice-mobile').selectedIndex = 0; $('cleanup-period').selectedIndex = 3;
    chooseTheme('light'); renderIdentities(); render();
  };
  for (const [page, title] of [['board','Доска'],['settings','Настройки']]) {
    const element = $(`${page}-page`); [...element.children].forEach((child) => { child.hidden = true; });
    element.insertAdjacentHTML('afterbegin', `<div class="production-reference"><h1>${title}</h1><p>Оставляем текущую версию рабочего приложения без редизайна. Этот экран исключён из нового концепта.</p></div>`);
    document.querySelectorAll(`nav [data-page="${page}"]`).forEach((button) => { button.title = 'Оставляем текущую версию приложения'; if (button.closest('.side-nav')) button.insertAdjacentHTML('beforeend','<span class="scope-tag" aria-hidden="true">прод</span>'); });
  }
  reset();
})();
