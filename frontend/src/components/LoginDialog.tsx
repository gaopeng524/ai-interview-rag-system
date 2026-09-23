import { useState } from 'react';
import { X, LogIn, UserPlus } from 'lucide-react';

interface LoginDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onLoginSuccess: (token: string, username: string) => void;
}

export default function LoginDialog({ isOpen, onClose, onLoginSuccess }: LoginDialogProps) {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!username || !password) {
      setError('请输入用户名和密码');
      return;
    }

    setLoading(true);
    setError('');

    try {
      // 使用相对路径，走 vite 代理到 demo 后端
      if (mode === 'register') {
        // 注册
        const registerResp = await fetch('/users', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ username, password }),
        });
        if (!registerResp.ok) {
          throw new Error('注册失败（用户名可能已存在）');
        }
      }

      // 登录
      const loginResp = await fetch('/users/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
      });

      if (!loginResp.ok) {
        throw new Error('用户名或密码错误');
      }

      const token = await loginResp.text();
      onLoginSuccess(token, username);
      onClose();
    } catch (e: any) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-[100] flex items-center justify-center bg-black/50 backdrop-blur-sm">
      <div className="bg-white dark:bg-slate-800 rounded-2xl shadow-2xl w-full max-w-md mx-4 overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between p-6 border-b border-slate-100 dark:border-slate-700">
          <h2 className="text-xl font-bold text-slate-900 dark:text-white">
            {mode === 'login' ? '登录' : '注册'}
          </h2>
          <button
            onClick={onClose}
            className="p-2 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Form */}
        <form onSubmit={handleSubmit} className="p-6 space-y-4">
          <div>
            <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
              用户名
            </label>
            <input
              type="text"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              className="w-full px-4 py-2.5 border border-slate-200 dark:border-slate-600 rounded-xl bg-white dark:bg-slate-700 text-slate-900 dark:text-white focus:ring-2 focus:ring-primary-500 focus:border-transparent outline-none transition-all"
              placeholder="请输入用户名"
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
              密码
            </label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="w-full px-4 py-2.5 border border-slate-200 dark:border-slate-600 rounded-xl bg-white dark:bg-slate-700 text-slate-900 dark:text-white focus:ring-2 focus:ring-primary-500 focus:border-transparent outline-none transition-all"
              placeholder="请输入密码"
            />
          </div>

          {error && (
            <div className="text-sm text-red-500 bg-red-50 dark:bg-red-900/20 px-4 py-2 rounded-lg">
              {error}
            </div>
          )}

          <button
            type="submit"
            disabled={loading}
            className="w-full py-2.5 bg-primary-500 hover:bg-primary-600 disabled:bg-primary-300 text-white rounded-xl font-medium transition-colors flex items-center justify-center gap-2"
          >
            {loading ? (
              <div className="w-5 h-5 border-2 border-white border-t-transparent rounded-full animate-spin" />
            ) : (
              <>
                {mode === 'login' ? <LogIn className="w-4 h-4" /> : <UserPlus className="w-4 h-4" />}
                {mode === 'login' ? '登录' : '注册'}
              </>
            )}
          </button>

          <div className="text-center">
            <button
              type="button"
              onClick={() => {
                setMode(mode === 'login' ? 'register' : 'login');
                setError('');
              }}
              className="text-sm text-primary-500 hover:text-primary-600 font-medium"
            >
              {mode === 'login' ? '没有账号？去注册' : '已有账号？去登录'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
