import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import axios from 'axios';

const API_BASE = import.meta.env.VITE_API_URL || '/api/v1';

interface Task {
  id: string;
  title: string;
  description?: string;
  priority: string;
  status: string;
  deadline?: string;
  estimateMinutes?: number;
  isDraft: boolean;
  source: string;
  groupId?: string;
  groupName?: string;
  tags: string[];
  createdAt: string;
  updatedAt: string;
  completedAt?: string;
}

interface FocusResponse {
  tasks: Task[];
}

interface DigestResponse {
  topTasks: Task[];
  totalTasks: number;
  completedToday: number;
  overdueTasks: number;
}

const getClient = () => {
  const token = localStorage.getItem('auth_token');
  return axios.create({
    baseURL: API_BASE,
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
};

export const useFocusTasks = () => {
  return useQuery({
    queryKey: ['tasks', 'focus'],
    queryFn: async () => {
      const response = await getClient().get<FocusResponse>('/tasks/focus');
      return response.data.tasks;
    },
    staleTime: 1000 * 60 * 2, // 2 min
  });
};

export const useDigestTasks = (date?: string) => {
  return useQuery({
    queryKey: ['tasks', 'digest', date || 'today'],
    queryFn: async () => {
      const params = date ? { date } : {};
      const response = await getClient().get<DigestResponse>('/tasks/digest', { params });
      return response.data;
    },
    staleTime: 1000 * 60 * 5, // 5 min
  });
};

export const useCompleteTask = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (taskId: string) => {
      await getClient().post(`/tasks/${taskId}/complete`);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
    },
  });
};

export const useDeleteTask = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (taskId: string) => {
      await getClient().delete(`/tasks/${taskId}`);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
    },
  });
};

export const useAllTasks = (date?: string) => {
  return useQuery({
    queryKey: ['tasks', 'all', date],
    queryFn: async () => {
      const params = date ? { date } : {};
      const response = await getClient().get<DigestResponse>('/tasks/digest', { params });
      return response.data.topTasks;
    },
    staleTime: 1000 * 60 * 2,
  });
};

export interface CreateTaskRequest {
  title: string;
  description?: string;
  priority: string;
  deadline?: string;
  estimateMinutes?: number;
  groupId?: string;
  groupName?: string;
  tags?: string[];
}

export interface GroupResponse {
  id: string;
  name: string;
  color?: string;
  icon?: string;
}

export interface CreateGroupRequest {
  name: string;
  color?: string;
  icon?: string;
}

export interface ParsedTask {
  title: string;
  priority: string;
  estimateMinutes?: number;
  groupId?: string;
  tags?: string[];
}

export interface ParseResponse {
  tasks: ParsedTask[];
}

export const useParseText = () => {
  return useMutation({
    mutationFn: async (text: string) => {
      const response = await getClient().post<ParseResponse>('/tasks/parse-text', {
        text,
        userTimezone: 'Europe/Moscow',
        userLanguage: 'ru',
      });
      return response.data.tasks ?? [];
    },
  });
};

export const useCreateTask = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (request: CreateTaskRequest) => {
      const response = await getClient().post<Task>('/tasks/quick', request);
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
    },
  });
};

interface TasksPage {
  content: Task[];
  totalElements: number;
}

export const useTasksList = () => {
  return useQuery({
    queryKey: ['tasks', 'list'],
    queryFn: async () => {
      const response = await getClient().get<TasksPage>('/tasks', {
        params: { size: 100, sort: 'createdAt,desc' },
      });
      return response.data.content;
    },
    staleTime: 1000 * 60 * 2,
  });
};

export interface UpdateTaskRequest {
  id: string;
  title?: string;
  description?: string;
  priority?: string;
  status?: string;
  deadline?: string | null;
  estimateMinutes?: number | null;
  groupId?: string | null;
}

export const useUpdateTask = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, ...patch }: UpdateTaskRequest) => {
      await getClient().patch(`/tasks/${id}`, patch);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['tasks'] });
    },
  });
};

export const useGroups = () => {
  return useQuery({
    queryKey: ['groups'],
    queryFn: async () => {
      const response = await getClient().get<GroupResponse[]>('/groups');
      return response.data;
    },
    staleTime: 1000 * 60 * 5,
  });
};

export const useCreateGroup = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (request: CreateGroupRequest) => {
      const response = await getClient().post<GroupResponse>('/groups', request);
      return response.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['groups'] });
    },
  });
};
