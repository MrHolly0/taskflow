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
  const settings = {name: 'Саша', timezone: 'Europe/Moscow', theme: 'light'};
  const dates = ['2026-09-29', '2026-09-30', '2026-10-01', '2026-10-02', '2026-10-03', '2026-10-04', '2026-10-05'];
  const shortDays = ['Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс', 'Пн'];
  const dateLabel = (value) => new Intl.DateTimeFormat('ru-RU', {day: 'numeric', month: 'short', timeZone: 'UTC'}).format(new Date(`${value.slice(0, 10)}T12:00:00Z`));
  const active = (task) => !task.archived && !['done', 'cancelled'].includes(task.status);
  const groupOptions = () => `<option value="">Без группы</option>${groups.map((group) => `<option>${e(group.name)}</option>`).join('')}`;
  const statusOptions = (includeCancelled = true) => Object.entries(statuses).filter(([key]) => includeCancelled || key !== 'cancelled').map(([key, value]) => `<option value="${key}">${value}</option>`).join('');
  const head = (title, subtitle, action = '') => `<header class="page-head"><div><h1>${title}</h1><p>${subtitle}</p></div>${action}</header>`;
  const addButton = (label, data) => `<button class="action primary" ${data}>${icon('plus')}${label}</button>`;
  const section = (id, content, extra = '') => `<section class="content secondary-page ${extra}" id="${id}-page" hidden>${content}</section>`;
  const field = (label, id, type = 'text', attrs = '') => `<label class="form-field" for="${id}">${label}<input class="field-control" id="${id}" type="${type}" ${attrs}></label>`;
  const toggle = (label, id, checked = false, note = '') => `<label class="setting-row setting-toggle"><span>${label}${note ? `<small>${note}</small>` : ''}</span><input class="switch" role="switch" type="checkbox" id="${id}" ${checked ? 'checked' : ''}></label>`;
  document.querySelector('.workspace').insertAdjacentHTML('beforeend', [
    section('all', `${head('Все задачи', 'Всё на месте. Найди нужное или добавь новое.', addButton('Задача', 'data-new-task'))}
      <form class="inline-form" id="new-task-form" hidden><h2>Новая задача</h2><div class="form-grid">${field('Название', 'new-task-title', 'text', 'required maxlength="500"')}<label>Группа<select class="field-control" id="new-task-group"></select></label></div><div class="actions"><button class="action primary">Добавить</button><button class="action" type="button" data-close-new>Отмена</button></div></form>
      <div class="toolbar"><label class="search-field">${icon('search')}<input type="search" id="task-search" aria-label="Поиск задач" placeholder="Найти задачу"></label><select class="field-control" id="list-group" aria-label="Фильтр по группе"></select><select class="field-control" id="list-status" aria-label="Фильтр по статусу"><option value="">Все статусы</option>${statusOptions()}</select></div>
      <div class="filter-strip" aria-label="Период задач">${[['now','Сейчас'],['today','Сегодня'],['week','Неделя'],['all','Все']].map(([key, label]) => `<button data-period="${key}" aria-pressed="${key === 'all'}">${label}</button>`).join('')}</div><p class="page-note" id="list-caption"></p><div id="all-list"></div>`),
    section('board', `${head('Доска', 'Три состояния. Двигай задачи в своём темпе.', addButton('Задача', 'data-new-task'))}<div class="filter-strip board-mobile-tabs" id="lane-tabs" aria-label="Колонка доски"></div><div class="board-grid" id="board-grid"></div>`),
    section('groups', `${head('Группы', 'Учёба, работа, личное — у каждого свой раздел.', addButton('Группа', 'data-new-group'))}<form class="inline-form" id="group-form" hidden><h2 id="group-form-heading">Новая группа</h2><div class="form-grid">${field('Название', 'group-name', 'text', 'required maxlength="100"')}<label>Значок<select class="field-control" id="group-symbol"><option value="groups">Папка</option><option value="list">Список</option><option value="focus">Фокус</option><option value="clock">Часы</option></select></label><div class="wide"><p class="page-note">Цвет метки</p><div class="color-choices">${colors.map((color, index) => `<button type="button" class="color-choice" data-group-color="${color}" style="--group-color:${color}" aria-label="${colorNames[index]}" aria-pressed="${index === 0}"><span></span></button>`).join('')}</div></div></div><p class="field-error" id="group-error" role="alert" hidden></p><div class="actions"><button class="action primary">Сохранить</button><button class="action" type="button" data-close-group>Отмена</button></div></form><div id="group-list"></div>`),
    section('stats', `${head('Статистика', '29 сентября — 5 октября · последние 7 дней')}<p class="stats-summary" id="stats-summary"></p><div class="chart-shell"><div class="chart-title"><h2>Как прошла неделя</h2><span class="page-note">Нажми на день</span></div><div class="chart-legend"><span><i class="legend-square created"></i>Добавлено</span><span><i class="legend-square"></i>Завершено</span></div><div class="week-chart" id="week-chart"></div><div class="day-insight" id="day-insight" aria-live="polite"></div></div><div class="stats-footer" id="stats-footer"></div>`),
    section('settings', `${head('Настройки', 'Подстрой Мунина под себя.')}<p class="page-note">Изменения сохраняются в этом примере до сброса страницы.</p><section class="settings-section"><h2>Профиль</h2><form id="profile-form"><label class="setting-row"><span>Как к тебе обращаться</span><div class="toolbar"><input id="profile-name" class="field-control" aria-label="Имя" maxlength="64" required value="Саша"><button class="action primary">Сохранить</button></div></label><p class="status-note" id="profile-status" role="status"></p></form><label class="setting-row"><span>Часовой пояс<small>Даты и напоминания в приложении</small></span><select class="field-control" id="timezone"><option value="Europe/Moscow">Москва · UTC+3</option><option value="Europe/Kaliningrad">Калининград · UTC+2</option><option value="Asia/Yekaterinburg">Екатеринбург · UTC+5</option><option value="Asia/Novosibirsk">Новосибирск · UTC+7</option><option value="Asia/Vladivostok">Владивосток · UTC+10</option></select></label></section>
      <section class="settings-section"><h2>Напоминания</h2>${toggle('Получать напоминания', 'notify-master', true)}${toggle('В Telegram', 'notify-telegram', true)}${toggle('На почту', 'notify-email', false, 'Нужен подключённый адрес')}${toggle('В браузере', 'notify-push', false, 'Разрешения и доставка в примере не подключены')}<label class="setting-row"><span>До срока задачи</span><select class="field-control" id="reminder-lead"><option>За 15 минут</option><option selected>За 30 минут</option><option>За час</option><option>За 2 часа</option><option>За день</option></select></label>${toggle('Повторять для срочных задач', 'notify-urgent', false, 'Каждые 15 минут до завершения')}</section>
      <section class="settings-section"><h2>Внешний вид и голос</h2><div class="setting-row"><span>Тема</span><div class="filter-strip">${[['light','День'],['dark','Ночь'],['system','Система']].map(([key, name]) => `<button data-theme-choice="${key}" aria-pressed="${key === 'light'}">${name}</button>`).join('')}</div></div><label class="setting-row"><span>Запись на компьютере</span><select class="field-control" id="voice-desktop"><option>Заканчивать после паузы</option><option>Начать и остановить кнопкой</option></select></label><label class="setting-row"><span>Запись на телефоне</span><select class="field-control" id="voice-mobile"><option>Удерживать кнопку</option><option>Начать и остановить кнопкой</option><option>Заканчивать после паузы</option></select></label></section>
      <section class="settings-section"><h2>Завершённые задачи</h2><label class="setting-row"><span>Убирать из списков<small>Выполненные и отменённые задачи</small></span><select class="field-control" id="cleanup-period"><option>Не убирать</option><option>Через 7 дней</option><option>Через 14 дней</option><option selected>Через 30 дней</option><option>Через 90 дней</option></select></label><div class="setting-row"><span>Очистить списки сейчас<small>Активные задачи останутся на месте</small></span><button class="action" data-cleanup>Убрать завершённые</button></div><div class="inline-form" id="cleanup-confirm" hidden><h2>Убрать завершённые и отменённые?</h2><p class="page-note">В примере они скроются из списка и доски. Итоги недели сохранятся.</p><div class="actions"><button class="action primary" data-confirm-cleanup>Убрать</button><button class="action" data-cancel-cleanup>Отмена</button></div></div></section>`, 'settings-content'),
    section('integrations', `${head('Интеграции', 'Способы входа в один и тот же аккаунт.')}<p class="page-note">Демо подключения: код 123456. Сообщения и SMS не отправляются.</p><div id="provider-list"></div><p class="page-note">Если способ входа уже связан с другим аккаунтом, Мунин предложит объединение и покажет, сколько задач и групп будет перенесено, до подтверждения.</p>`, 'integrations-content'),
    section('more', `${head('Ещё', 'Остальное — когда понадобится.')}<div>${[['stats','stats','Статистика','Обзор недели'],['settings','settings','Настройки','Профиль, напоминания и внешний вид'],['integrations','plug','Интеграции','Telegram, почта и телефон']].map(([page, symbol, title, note]) => `<button class="menu-link" data-page="${page}">${icon(symbol)}<span>${title}<small>${note}</small></span>${icon('arrow')}</button>`).join('')}</div>`)
  ].join(''));

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
  const row = (task) => `<article class="list-row ${task.status === 'done' ? 'is-done' : ''}"><button class="task-check" data-toggle-task="${task.id}" aria-label="${['done', 'cancelled'].includes(task.status) ? 'Вернуть' : 'Завершить'}: ${e(task.title)}"><span class="check-ring">${icon('check')}</span></button><div class="row-body"><button data-open-task="${task.id}">${e(task.title)}</button><div class="row-meta">${task.group ? `<span>${dot(task.group)}${e(task.group)}</span>` : ''}<span class="${task.deadline && task.deadline < '2026-10-05' && active(task) ? 'critical' : ''}">${e(taskMeta(task))}</span>${task.minutes ? `<span>${task.minutes} мин</span>` : ''}${task.status === 'started' || task.status === 'cancelled' ? `<span class="badge">${statuses[task.status]}</span>` : ''}${task.priority === 'urgent' ? '<span class="critical">Срочно</span>' : ''}</div></div><button class="quiet-icon" data-open-task="${task.id}" aria-label="Редактировать: ${e(task.title)}">${icon('edit')}</button></article>`;
  function renderList() {
    $('list-group').innerHTML = `<option value="">Все группы</option>${groups.map((group) => `<option>${e(group.name)}</option>`).join('')}`; $('list-group').value = groupFilter;
    const filtered = tasks.filter((task) => !task.archived && task.title.toLocaleLowerCase('ru').includes(search.toLocaleLowerCase('ru')) && (!groupFilter || task.group === groupFilter) && (!statusFilter || (task.status === 'moved' ? 'todo' : task.status) === statusFilter) && (period === 'all' || active(task) && (period === 'now' ? task.priority === 'urgent' || task.status === 'started' || task.deadline && task.deadline < '2026-10-05' : !task.deadline || task.deadline <= (period === 'today' ? '2026-10-05T23:59' : '2026-10-11T23:59'))));
    $('list-caption').textContent = `${filtered.length} ${doneWord(filtered.length)}${period === 'today' || period === 'week' ? ' · включает просроченные задачи и задачи без срока' : ''}`;
    const sections = [['В работе', (task) => task.status === 'started'], ['К выполнению', (task) => ['todo', 'moved'].includes(task.status)], ['Завершено', (task) => task.status === 'done'], ['Отменено', (task) => task.status === 'cancelled']];
    $('all-list').innerHTML = filtered.length ? sections.map(([title, matches]) => { const items = filtered.filter(matches); return items.length ? `<section class="list-section"><h2>${title} · ${items.length}</h2>${items.map(row).join('')}</section>` : ''; }).join('') : `<div class="empty-state"><h2>Здесь пока пусто</h2><p>${search || groupFilter || statusFilter || period !== 'all' ? 'Попробуй другой запрос или убери фильтры.' : 'Добавь задачу или расскажи о ней Мунину.'}</p><button class="action" data-clear-filters>Сбросить фильтры</button></div>`;
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
  function renderGroups() {
    $('group-list').innerHTML = groups.map((group) => {
      const items = tasks.filter((task) => !task.archived && task.group === group.name), pending = items.filter(active), done = items.filter((task) => task.status === 'done');
      return `<section class="group-item"><div class="group-header"><span class="group-symbol">${icon(group.symbol)}</span><button class="group-open" data-expand-group="${e(group.name)}" aria-expanded="${expandedGroup === group.name}"><h2>${dot(group.name)}${e(group.name)}</h2><p>${pending.length} в планах · ${done.length} завершено</p><p class="group-preview">${pending.length ? e(pending.slice(0,2).map((task) => task.title).join(' · ')) : 'Можно добавить первое дело'}</p></button><button class="quiet-icon" data-edit-group="${e(group.name)}" aria-label="Изменить группу ${e(group.name)}">${icon('edit')}</button></div>${expandedGroup === group.name ? `<div class="group-detail">${items.map(row).join('') || '<p class="page-note">В группе пока нет задач.</p>'}<button class="action" data-new-task data-task-group="${e(group.name)}">${icon('plus')} Добавить задачу</button></div>` : ''}</section>`;
    }).join('');
  }
  function renderStats() {
    const days = dates.map((date, index) => ({date, created: tasks.filter((task) => task.createdDay === index).length, done: tasks.filter((task) => task.status === 'done' && task.completedDay === index).length}));
    const created = days.reduce((sum, day) => sum + day.created, 0), done = days.reduce((sum, day) => sum + day.done, 0), used = days.filter((day) => day.created || day.done).length;
    $('stats-summary').innerHTML = `За неделю ты завершил <strong>${done} ${doneWord(done)}</strong> и добавил ${created}. Занимался задачами в ${used} из 7 дней.`;
    const max = Math.max(1, ...days.flatMap((day) => [day.created, day.done]));
    $('week-chart').innerHTML = days.map((day, index) => `<button class="chart-day" data-chart-day="${index}" aria-pressed="${selectedDay === index}" title="${dateLabel(day.date)}: добавлено ${day.created}, завершено ${day.done}" aria-label="${dateLabel(day.date)}: добавлено ${day.created}, завершено ${day.done}"><span class="chart-bars" aria-hidden="true"><i class="chart-bar created" style="height:${day.created / max * 100}%;${day.created ? '' : 'visibility:hidden'}"></i><i class="chart-bar" style="height:${day.done / max * 100}%;${day.done ? '' : 'visibility:hidden'}"></i></span><span>${shortDays[index]}</span><strong>${day.date.slice(8).replace(/^0/, '')}</strong></button>`).join('');
    const day = days[selectedDay];
    $('day-insight').innerHTML = `<strong>${dateLabel(day.date)} · добавлено ${day.created}, завершено ${day.done}</strong><p>${day.done ? e(tasks.filter((task) => task.status === 'done' && task.completedDay === selectedDay).map((task) => task.title).join(' · ')) : 'Завершённых задач в этот день нет.'}</p>`;
    const overdue = tasks.filter((task) => active(task) && task.deadline && task.deadline < '2026-10-05').length;
    $('stats-footer').innerHTML = `<p>${overdue ? `${overdue} ${doneWord(overdue)} с прошедшим сроком.` : 'Задач с прошедшим сроком нет.'}</p><p class="page-note">${overdue ? 'Можно пересмотреть сроки или выбрать, чем заняться сейчас.' : 'Здесь учитываются даты создания и завершения, а не длина серии.'}</p>${overdue ? '<button class="text-action" data-show-urgent>Посмотреть задачи</button>' : ''}`;
  }
  function renderIdentities() {
    const providers = [['telegram','Telegram','chat','Вход через Telegram'], ['email','Почта','mail','Вход по одноразовому коду'], ['phone','Телефон','phone','Вход по коду из SMS']];
    const total = Object.values(identities).filter(Boolean).length;
    $('provider-list').innerHTML = providers.map(([key, name, symbol, note]) => {
      const connected = identities[key], opened = binding?.key === key;
      const targetName = key === 'email' ? 'почту' : key === 'phone' ? 'телефон' : 'Telegram';
      const form = opened ? binding.step === 'telegram' ? `<div class="inline-form"><h2>Подключить Telegram</h2><p class="page-note">В приложении здесь открывается вход через Telegram. В примере возвращается @sasha.</p><div class="actions"><button class="action primary" data-confirm-telegram>Подключить</button><button class="action" data-cancel-bind>Отмена</button></div></div>` : binding.step === 'remove' ? `<div class="inline-form"><h2>Отключить ${targetName}?</h2><p class="page-note">Для входа останутся другие подключённые способы.</p><div class="actions"><button class="action primary" data-confirm-unbind="${key}">Отключить</button><button class="action" data-cancel-bind>Отмена</button></div></div>` : `<form class="inline-form" id="bind-form"><h2>${binding.step === 'code' ? 'Подтверди подключение' : `Подключить ${targetName}`}</h2>${binding.step === 'code' ? `<p class="page-note">${e(binding.value)} · демо-код 123456</p>${field('Код', 'bind-value', 'text', 'inputmode="numeric" pattern="[0-9]{6}" maxlength="6" required autocomplete="one-time-code"')}` : field(key === 'email' ? 'Адрес почты' : 'Номер телефона', 'bind-value', key === 'email' ? 'email' : 'tel', `required ${key === 'phone' ? 'pattern="[+0-9 ()-]{10,20}"' : ''} placeholder="${key === 'email' ? 'name@example.test' : '+7 900 000-00-00'}"`)}<p class="field-error" id="bind-error" role="alert" hidden></p><div class="actions"><button class="action primary">${binding.step === 'code' ? 'Подключить' : 'Продолжить'}</button><button class="action" type="button" data-cancel-bind>Отмена</button></div></form>` : '';
      return `<section class="provider"><span class="provider-symbol">${icon(symbol)}</span><div class="provider-body"><div class="provider-heading"><h2>${name}</h2>${connected ? '<span class="badge">Подключено</span>' : ''}</div><p>${connected ? e(connected) : note}</p>${connected && total === 1 ? '<p>Единственный способ входа. Сначала подключи ещё один.</p>' : ''}${form}</div>${opened ? '' : `<button class="action" data-bind="${key}" ${connected && total === 1 ? 'disabled' : ''}>${connected ? 'Отключить' : 'Подключить'}</button>`}</section>`;
    }).join('');
    syncNotificationAvailability();
  }
  function syncNotificationAvailability() {
    const master = $('notify-master').checked;
    $('notify-telegram').disabled = !master || !identities.telegram;
    $('notify-email').disabled = !master || !identities.email;
    $('notify-push').disabled = true; $('notify-urgent').disabled = !master; $('reminder-lead').disabled = !master;
  }
  function refreshPages() { renderList(); renderBoard(); renderGroups(); renderStats(); }
  const originalRender = render, originalShowPage = showPage, originalReset = reset;
  render = (revealNext = false) => { observeTasks(); originalRender(revealNext); refreshPages(); };
  showPage = (page) => {
    if (!labels[page]) return;
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
    if (data.period) { period = data.period; document.querySelectorAll('[data-period]').forEach((item) => item.setAttribute('aria-pressed', String(item.dataset.period === period))); renderList(); }
    if ('clearFilters' in data) { period = 'all'; search = ''; groupFilter = ''; statusFilter = ''; $('task-search').value = ''; $('list-status').value = ''; document.querySelectorAll('[data-period]').forEach((item) => item.setAttribute('aria-pressed', String(item.dataset.period === 'all'))); renderList(); $('task-search').focus(); }
    if (data.lane) { lane = data.lane; renderBoard(); document.querySelector(`[data-lane="${lane}"]`).focus(); }
    if ('newGroup' in data) openGroup();
    if (data.editGroup) openGroup(data.editGroup);
    if ('closeGroup' in data) { $('group-form').hidden = true; document.querySelector('#groups-page [data-new-group]').focus(); }
    if (data.expandGroup) { expandedGroup = expandedGroup === data.expandGroup ? null : data.expandGroup; renderGroups(); [...document.querySelectorAll('[data-expand-group]')].find((item) => item.dataset.expandGroup === data.expandGroup)?.focus(); }
    if (data.groupColor) { groupColor = data.groupColor; syncGroupColors(); }
    if (data.chartDay) { selectedDay = Number(data.chartDay); renderStats(); document.querySelector(`[data-chart-day="${selectedDay}"]`).focus(); }
    if ('showUrgent' in data) { period = 'now'; search = ''; groupFilter = ''; statusFilter = ''; $('task-search').value = ''; $('list-status').value = ''; document.querySelectorAll('[data-period]').forEach((item) => item.setAttribute('aria-pressed', String(item.dataset.period === period))); renderList(); showPage('all'); }
    if (data.themeChoice) chooseTheme(data.themeChoice);
    if ('cleanup' in data) { $('cleanup-confirm').hidden = false; document.querySelector('[data-confirm-cleanup]').focus(); }
    if ('cancelCleanup' in data) { $('cleanup-confirm').hidden = true; document.querySelector('[data-cleanup]').focus(); }
    if ('confirmCleanup' in data) { tasks.filter((task) => ['done','cancelled'].includes(task.status)).forEach((task) => { task.archived = true; }); render(); $('cleanup-confirm').hidden = true; document.querySelector('[data-cleanup]').focus(); notice('Завершённые и отменённые задачи убраны из списков.'); }
    if (data.bind) { binding = {key: data.bind, step: identities[data.bind] ? 'remove' : data.bind === 'telegram' ? 'telegram' : 'address'}; renderIdentities(); ($('bind-value') || document.querySelector('[data-confirm-unbind], [data-confirm-telegram]'))?.focus(); }
    if ('confirmTelegram' in data) { identities.telegram = '@sasha'; binding = null; renderIdentities(); notice('Telegram подключён в примере.'); }
    if ('cancelBind' in data) { binding = null; renderIdentities(); }
    if (data.confirmUnbind) { if (Object.values(identities).filter(Boolean).length < 2) return; identities[data.confirmUnbind] = ''; binding = null; renderIdentities(); notice('Способ входа отключён в примере.'); }
  });
  $('task-search').addEventListener('input', (event) => { search = event.target.value; renderList(); });
  $('list-group').addEventListener('change', (event) => { groupFilter = event.target.value; renderList(); });
  $('list-status').addEventListener('change', (event) => { statusFilter = event.target.value; renderList(); });
  $('new-task-form').addEventListener('submit', (event) => {
    event.preventDefault(); const title = $('new-task-title').value.trim(); if (!title) return;
    tasks.push({id: nextTaskId++, title, group: $('new-task-group').value, status: 'todo', minutes: null, meta: 'Без срока', createdDay: 6});
    search = ''; groupFilter = ''; statusFilter = ''; period = 'all'; $('task-search').value = ''; $('list-status').value = ''; $('new-task-title').value = ''; $('new-task-form').hidden = true;
    document.querySelectorAll('[data-period]').forEach((item) => item.setAttribute('aria-pressed', String(item.dataset.period === 'all'))); render(); notice('Задача добавлена.'); document.querySelector('#all-page [data-new-task]').focus();
  });
  $('group-form').addEventListener('submit', (event) => {
    event.preventDefault(); const name = $('group-name').value.trim(); if (!name) return;
    if (groups.some((group) => group.name.toLocaleLowerCase('ru') === name.toLocaleLowerCase('ru') && group.name !== editingGroup)) { $('group-error').textContent = 'Группа с таким названием уже есть.'; $('group-error').hidden = false; return; }
    if (editingGroup) { const group = groups.find((item) => item.name === editingGroup); tasks.filter((task) => task.group === editingGroup).forEach((task) => { task.group = name; }); if (groupFilter === editingGroup) groupFilter = name; if (expandedGroup === editingGroup) expandedGroup = name; Object.assign(group, {name, color: groupColor, symbol: $('group-symbol').value}); }
    else groups.push({name, color: groupColor, symbol: $('group-symbol').value});
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
  $('provider-list').addEventListener('submit', (event) => { if (event.target.id !== 'bind-form') return; event.preventDefault(); const value = $('bind-value').value.trim(); if (binding.step === 'address') { binding.value = value; binding.step = 'code'; renderIdentities(); $('bind-value').focus(); } else if (value !== '123456') { $('bind-error').textContent = 'В этом примере код — 123456.'; $('bind-error').hidden = false; } else { identities[binding.key] = binding.value; binding = null; renderIdentities(); notice('Способ входа подключён в примере.'); } });

  reset = () => {
    knownTasks.clear(); groups = [{name:'Учёба',color:colors[0],symbol:'groups'},{name:'Личное',color:colors[2],symbol:'focus'},{name:'Работа',color:colors[3],symbol:'list'},{name:'Идеи',color:colors[1],symbol:'groups'}];
    period = 'all'; search = ''; groupFilter = ''; statusFilter = ''; expandedGroup = null; lane = 'todo'; selectedDay = 6; binding = null; identities = {telegram:'@sasha',email:'',phone:''};
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
  reset();
})();
