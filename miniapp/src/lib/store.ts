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
  groupId?: string;
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


export const useStore = create<AppState>((set) => ({
  isAuthenticated: false,
  user: null,
  tasks: [],
  groups: [],

  login: (user) => set({ isAuthenticated: true, user }),
  logout: () => {
    localStorage.removeItem('auth_token');
    localStorage.removeItem('refresh_token');
    set({ isAuthenticated: false, user: null });
  },
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
