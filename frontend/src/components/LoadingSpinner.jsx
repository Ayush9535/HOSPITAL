import React from 'react';

const LoadingSpinner = ({ size = 'md', variant = 'primary', text = 'Loading...' }) => {
  const sizes = {
    sm: 'w-6 h-6',
    md: 'w-12 h-12',
    lg: 'w-16 h-16',
    xl: 'w-20 h-20',
  };

  const variants = {
    primary: {
      outer: 'border-primary-200',
      inner: 'border-t-primary-600',
      accent: 'border-t-secondary-400',
    },
    secondary: {
      outer: 'border-secondary-200',
      inner: 'border-t-secondary-600',
      accent: 'border-t-primary-400',
    },
    neutral: {
      outer: 'border-neutral-200',
      inner: 'border-t-neutral-600',
      accent: 'border-t-neutral-400',
    },
  };

  const theme = variants[variant] || variants.primary;

  return (
    <div className="flex flex-col items-center justify-center gap-4">
      <div className="relative">
        {/* Outer ring */}
        <div
          className={`${sizes[size]} border-4 ${theme.outer} ${theme.inner} rounded-full animate-spin`}
        ></div>

        {/* Inner ring - counter rotation */}
        <div
          className={`absolute inset-0 ${sizes[size]} border-4 border-transparent ${theme.accent} rounded-full animate-spin`}
          style={{
            animationDirection: 'reverse',
            animationDuration: '1.5s',
          }}
        ></div>

        {/* Center dot */}
        <div className="absolute inset-0 flex items-center justify-center">
          <div className="w-2 h-2 bg-gray-900 rounded-full animate-pulse"></div>
        </div>
      </div>

      {text && <p className="text-sm font-medium text-neutral-600 animate-pulse">{text}</p>}
    </div>
  );
};

export default LoadingSpinner;
