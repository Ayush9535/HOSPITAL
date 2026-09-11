import React, { useEffect, useState } from 'react';
import hospitalService from '../services/hospitalService';
import { SkeletonFeed } from './Skeleton';

const ActivityFeed = () => {
  const [logs, setLogs] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    fetchLogs();
    // Auto-refresh every 30 seconds
    const interval = setInterval(fetchLogs, 30000);
    return () => clearInterval(interval);
  }, []);

  // Also expose a way to refresh externally if needed (e.g. via context or prop)
  const fetchLogs = async () => {
    try {
      const data = await hospitalService.getAuditLogs(null, null, 20);
      setLogs(Array.isArray(data) ? data : []);
    } catch (err) {
      console.error('Failed to load activity logs', err);
      setError('Failed to load activity.');
    } finally {
      setLoading(false);
    }
  };

  const getIcon = (action) => {
    if (action.includes('DELETED')) return null;
    if (action.includes('CREATED')) return null;
    if (action.includes('UPDATED')) return null;
    if (action.includes('STATUS')) return null;
    return null;
  };

  const formatTime = (timestamp) => {
    if (!timestamp) return '';
    const date = new Date(timestamp);
    return date.toLocaleString();
  };

  if (loading)
    return (
      <div className="p-4">
        <SkeletonFeed count={4} />
      </div>
    );
  if (error) return <div className="text-red-500 text-sm p-4">{error}</div>;
  if (logs.length === 0) return <div className="text-gray-400 text-sm p-4">No recent activity</div>;

  return (
    <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
      <div className="px-6 py-4 border-b border-gray-200 flex justify-between items-center">
        <h3 className="font-medium text-gray-900">Recent Activity</h3>
        <button onClick={fetchLogs} className="text-sm text-gray-600 hover:text-gray-900">
          Refresh
        </button>
      </div>
      <div className="max-h-[400px] overflow-y-auto">
        <ul className="divide-y divide-gray-200">
          {logs.map((log) => (
            <li key={log.id} className="px-6 py-4 hover:bg-gray-50 transition-colors">
              <div className="flex items-start gap-3">
                <div className="flex-1">
                  <p className="text-sm text-gray-900 font-medium">
                    {log.details.split('. Reason:')[0]}
                  </p>
                  {log.reason && (
                    <p className="text-xs text-gray-600 mt-1 italic">&quot;{log.reason}&quot;</p>
                  )}
                  <div className="flex justify-between items-center mt-2">
                    <span className="text-xs text-gray-500">{formatTime(log.timestamp)}</span>
                    <span className="text-xs text-gray-700 bg-gray-100 px-2 py-0.5 rounded-full">
                      {log.performedBy}
                    </span>
                  </div>
                </div>
              </div>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
};

export default ActivityFeed;
