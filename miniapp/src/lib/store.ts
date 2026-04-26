import { create } from 'zustand';

export type Priority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT';
export type Status = 'TODO' | 'IN_PROGRESS' | 'DONE' | 'CANCELLED';

export function getStatusLabel(status: Status): string {
  const labels: Record<Status, string> = {
    TODO: 'К выполнению',
    IN_PROGRESS: 'В работе',
    DONE: 'Готово',
    CANCELLED: 'Отменено',
  };
  return labels[status];
}

export function getPriorityLabel(priority: Priority): string {
  const labels: Record<Priority, string> = {
    URGENT: 'Срочно',
    HIGH: 'Важно',
    MEDIUM: 'Средний',
    LOW: 'Когда будет время',
  };
  return labels[priority];
}

export interface Task {
  id: string;
  title: string;
  description?: string;
  priority: Priority;
  status: Status;
  deadline?: string;
  group?: string;
  estimatedTime?: number;
  createdAt: string;
  completedAt?: string;
}

export interface Group {
  id: string;
  name: string;
  icon: string;
  color?: string;
}

export interface User {
  id: string;
  name: string;
  username: string;
  avatar?: string;
}

interface AppState {
  isAuthenticated: boolean;
  user: User | null;
  tasks: Task[];
  groups: Group[];
  login: (user: User) => void;
  logout: () => void;
  setAuthenticated: (auth: boolean) => void;
  addTask: (task: Omit<Task, 'id' | 'createdAt'>) => void;
  updateTask: (id: string, updates: Partial<Task>) => void;
  deleteTask: (id: string) => void;
  completeTask: (id: string) => void;
  addGroup: (group: Omit<Group, 'id'>) => void;
  deleteGroup: (id: string) => void;
}

const mockTasks: Task[] = [
  {
    id: '1',
    title: 'Доделать презентацию',
    description: 'Слайды для встречи с командой. Нужно добавить секцию с аналитикой и финальный слайд с выводами.',
    priority: 'URGENT',
    status: 'TODO',
    deadline: new Date(Date.now() + 4 * 60 * 60 * 1000).toISOString(),
    group: 'Работа',
    estimatedTime: 60,
    createdAt: new Date().toISOString(),
  },
  {
    id: '2',
    title: 'Позвонить маме',
    description: 'Обсудить планы на выходные и узнать как дела у бабушки.',
    priority: 'MEDIUM',
    status: 'TODO',
    deadline: new Date(Date.now() + 2 * 24 * 60 * 60 * 1000).toISOString(),
    group: 'Семья',
    estimatedTime: 15,
    createdAt: new Date().toISOString(),
  },
  {
    id: '3',
    title: 'Купить молоко',
    priority: 'LOW',
    status: 'TODO',
    deadline: new Date(Date.now() + 1 * 24 * 60 * 60 * 1000).toISOString(),
    group: 'Покупки',
    estimatedTime: 10,
    createdAt: new Date().toISOString(),
  },
  {
    id: '4',
    title: 'Подготовиться к экзамену',
    description: 'Повторить темы: алгоритмы, структуры данных, базы данных.',
    priority: 'HIGH',
    status: 'IN_PROGRESS',
    deadline: new Date(Date.now() + 3 * 24 * 60 * 60 * 1000).toISOString(),
    group: 'Учёба',
    estimatedTime: 120,
    createdAt: new Date(Date.now() - 2 * 24 * 60 * 60 * 1000).toISOString(),
  },
  {
    id: '5',
    title: 'Написать отчёт по маркетингу',
    description: 'Анализ конкурентов и предложения по улучшению стратегии.',
    priority: 'MEDIUM',
    status: 'TODO',
    deadline: new Date(Date.now() + 5 * 24 * 60 * 60 * 1000).toISOString(),
    group: 'Работа',
    estimatedTime: 90,
    createdAt: new Date().toISOString(),
  },
  {
    id: '6',
    title: 'Сделать зарядку',
    priority: 'LOW',
    status: 'TODO',
    group: 'Здоровье',
    estimatedTime: 20,
    createdAt: new Date().toISOString(),
  },
  {
    id: '7',
    title: 'Прочитать статью по TypeScript',
    description: 'Статья про новые фичи в TS 5.0',
    priority: 'LOW',
    status: 'DONE',
    group: 'Учёба',
    estimatedTime: 30,
    createdAt: new Date(Date.now() - 3 * 24 * 60 * 60 * 1000).toISOString(),
    completedAt: new Date(Date.now() - 1 * 24 * 60 * 60 * 1000).toISOString(),
  },
  {
    id: '8',
    title: 'Оплатить интернет',
    priority: 'HIGH',
    status: 'TODO',
    deadline: new Date(Date.now() + 2 * 24 * 60 * 60 * 1000).toISOString(),
    group: 'Финансы',
    estimatedTime: 5,
    createdAt: new Date().toISOString(),
  },
];

const mockGroups: Group[] = [
  { id: '1', name: 'Работа', icon: 'briefcase', color: 'blue' },
  { id: '2', name: 'Учёба', icon: 'school', color: 'purple' },
  { id: '3', name: 'Семья', icon: 'users', color: 'green' },
  { id: '4', name: 'Покупки', icon: 'shopping', color: 'orange' },
  { id: '5', name: 'Здоровье', icon: 'run', color: 'red' },
  { id: '6', name: 'Финансы', icon: 'cash', color: 'yellow' },
];

export const useStore = create<AppState>((set) => ({
  isAuthenticated: false,
  user: null,
  tasks: mockTasks,
  groups: mockGroups,

  login: (user) => set({ isAuthenticated: true, user }),
  logout: () => set({ isAuthenticated: false, user: null }),
  setAuthenticated: (auth) => set({ isAuthenticated: auth }),

  addTask: (task) =>
    set((state) => ({
      tasks: [
        ...state.tasks,
        {
          ...task,
          id: Math.random().toString(36).substr(2, 9),
          createdAt: new Date().toISOString(),
        },
      ],
    })),

  updateTask: (id, updates) =>
    set((state) => ({
      tasks: state.tasks.map((task) =>
        task.id === id ? { ...task, ...updates } : task
      ),
    })),

  deleteTask: (id) =>
    set((state) => ({
      tasks: state.tasks.filter((task) => task.id !== id),
    })),

  completeTask: (id) =>
    set((state) => ({
      tasks: state.tasks.map((task) =>
        task.id === id
          ? { ...task, status: 'DONE', completedAt: new Date().toISOString() }
          : task
      ),
    })),

  addGroup: (group) =>
    set((state) => ({
      groups: [
        ...state.groups,
        {
          ...group,
          id: Math.random().toString(36).substr(2, 9),
        },
      ],
    })),

  deleteGroup: (id) =>
    set((state) => ({
      groups: state.groups.filter((g) => g.id !== id),
    })),
}));
