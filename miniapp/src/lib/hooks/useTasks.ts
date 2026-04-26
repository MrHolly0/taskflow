import { useQuery, useMutation } from '@tanstack/react-query';
import axios from 'axios';

const API_BASE = import.meta.env.VITE_API_URL || 'http://localhost:8080/api/v1';

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
  return useMutation({
    mutationFn: async (taskId: string) => {
      await getClient().post(`/tasks/${taskId}/complete`);
    },
  });
};

export const useDeleteTask = () => {
  return useMutation({
    mutationFn: async (taskId: string) => {
      await getClient().delete(`/tasks/${taskId}`);
    },
  });
};
