import { SkeletonDashboard } from './Skeleton';
import StatusBadge from './StatusBadge';

// Overview Dashboard Component for Hospital Admin
const OverviewDashboard = ({ stats, todaysAppointments, loading }) => {
  if (loading) {
    return <SkeletonDashboard statCount={3} tableRows={5} tableCols={4} />;
  }

  const statCards = [
    {
      title: 'Total Patients',
      value: stats.totalPatients || 0,
      icon: null,
      color: 'primary',
      bgGradient: null,
      iconBg: 'bg-gray-100',
      change: '+12%',
      changeType: 'positive',
    },
    {
      title: 'Total Doctors',
      value: stats.totalDoctors || 0,
      icon: null,
      color: 'success',
      bgGradient: null,
      iconBg: 'bg-gray-100',
      change: '+3%',
      changeType: 'positive',
    },
    {
      title: "Today's Appointments",
      value: stats.todaysAppointments || 0,
      icon: null,
      color: 'secondary',
      bgGradient: null,
      iconBg: 'bg-gray-100',
      change: '+8%',
      changeType: 'positive',
    },
  ];

  return (
    <div className="space-y-8">
      {/* Enhanced Stats Cards */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
        {statCards.map((card, index) => (
          <div
            key={card.title}
            className="bg-white rounded-lg border border-gray-200 p-6"
            style={{ animationDelay: `${index * 100}ms` }}
          >
            <div className="flex items-center justify-between">
              <div className="flex-1">
                <p className="text-sm font-medium text-gray-600 mb-2">{card.title}</p>
                <div className="flex items-baseline gap-2">
                  <p className="text-3xl font-bold text-gray-900">{card.value.toLocaleString()}</p>
                  <span className="text-xs font-medium px-2 py-1 rounded-full bg-gray-100 text-gray-600">
                    {card.change}
                  </span>
                </div>
              </div>
            </div>

            {/* Progress bar */}
            <div className="mt-4 w-full bg-gray-100 rounded-full h-1.5 overflow-hidden">
              <div
                className="h-full bg-gray-900 rounded-full transition-all duration-1000 ease-out"
                style={{ width: `${Math.min((card.value / 100) * 100, 100)}%` }}
              ></div>
            </div>
          </div>
        ))}
      </div>

      {/* Enhanced Today's Appointments Table */}
      <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
        <div className="bg-white px-8 py-6 border-b border-gray-200">
          <div className="flex items-center justify-between">
            <div>
              <h3 className="text-xl font-bold text-gray-900">Today&apos;s Appointments</h3>
              <p className="text-sm text-gray-600 mt-1">
                Quick overview of all scheduled appointments for today
              </p>
            </div>
          </div>
        </div>

        {todaysAppointments.length > 0 ? (
          <div className="overflow-x-auto">
            <table className="min-w-full">
              <thead className="bg-gray-50">
                <tr>
                  <th className="px-8 py-4 text-left text-xs font-medium text-gray-600 uppercase tracking-wider">
                    Patient
                  </th>
                  <th className="px-6 py-4 text-left text-xs font-medium text-gray-600 uppercase tracking-wider">
                    Doctor
                  </th>
                  <th className="px-6 py-4 text-left text-xs font-medium text-gray-600 uppercase tracking-wider">
                    Time
                  </th>
                  <th className="px-6 py-4 text-left text-xs font-medium text-gray-600 uppercase tracking-wider">
                    Status
                  </th>
                </tr>
              </thead>
              <tbody className="bg-white divide-y divide-gray-200">
                {todaysAppointments.map((appointment, index) => (
                  <tr
                    key={appointment.id}
                    className="hover:bg-gray-50 transition-colors duration-200"
                    style={{ animationDelay: `${index * 50}ms` }}
                  >
                    <td className="px-8 py-5 whitespace-nowrap">
                      <div className="flex items-center gap-3">
                        <div className="w-10 h-10 bg-gray-900 rounded-lg flex items-center justify-center text-white font-semibold text-sm">
                          {appointment.patientName?.charAt(0)?.toUpperCase()}
                        </div>
                        <div>
                          <p className="text-sm font-medium text-gray-900">
                            {appointment.patientName}
                          </p>
                          <p className="text-xs text-gray-600">ID: {appointment.patientId}</p>
                        </div>
                      </div>
                    </td>
                    <td className="px-6 py-5 whitespace-nowrap">
                      <span className="text-sm font-medium text-gray-700">
                        {appointment.doctorName}
                      </span>
                    </td>
                    <td className="px-6 py-5 whitespace-nowrap">
                      <span className="text-sm text-gray-600">
                        {new Date(appointment.appointmentDate).toLocaleDateString()}
                      </span>
                    </td>
                    <td className="px-6 py-5 whitespace-nowrap">
                      <StatusBadge status={appointment.status} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <div className="p-16 text-center">
            <div className="w-20 h-20 bg-gray-100 rounded-lg flex items-center justify-center mx-auto mb-4"></div>
            <h4 className="text-lg font-medium text-gray-900 mb-2">No appointments today</h4>
            <p className="text-gray-600 max-w-sm mx-auto">
              It looks like there are no appointments scheduled for today. Enjoy the quiet day!
            </p>
          </div>
        )}
      </div>
    </div>
  );
};

export default OverviewDashboard;
