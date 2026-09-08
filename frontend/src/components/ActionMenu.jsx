import React, { useState, useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';

const ActionMenu = ({ actions }) => {
  const [isOpen, setIsOpen] = useState(false);
  const [position, setPosition] = useState({ top: 0, left: 0 });
  const buttonRef = useRef(null);

  const visibleActions = actions.filter((action) => !action.hidden);

  useEffect(() => {
    const handleClickOutside = (event) => {
      if (
        buttonRef.current &&
        !buttonRef.current.contains(event.target) &&
        !event.target.closest('.action-menu-dropdown')
      ) {
        setIsOpen(false);
      }
    };

    const handleScroll = () => {
      if (isOpen) setIsOpen(false); // Close on scroll to avoid positioning issues
    };

    if (isOpen) {
      document.addEventListener('mousedown', handleClickOutside);
      window.addEventListener('scroll', handleScroll, true);
      window.addEventListener('resize', handleScroll);
    }
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
      window.removeEventListener('scroll', handleScroll, true);
      window.removeEventListener('resize', handleScroll);
    };
  }, [isOpen]);

  const toggleMenu = () => {
    if (!isOpen && buttonRef.current) {
      const rect = buttonRef.current.getBoundingClientRect();
      // Calculate position: align right edge of menu with right edge of button
      // w-48 is 12rem = 192px. We'll position it relative to viewport (fixed) or document (absolute).
      // Using fixed for simplicity with rects.
      setPosition({
        top: rect.bottom + 5,
        left: rect.right - 192, // 192px is width of w-48
      });
    }
    setIsOpen(!isOpen);
  };

  const menu = (
    <div
      className="action-menu-dropdown fixed z-[9999] w-48 rounded-md shadow-lg bg-white ring-1 ring-black ring-opacity-5 focus:outline-none transform transition-all duration-200 ease-out"
      style={{ top: position.top, left: position.left }}
    >
      <div className="py-1" role="menu" aria-orientation="vertical">
        {visibleActions.map((action, index) => (
          <button
            key={index}
            onClick={(e) => {
              e.stopPropagation();
              action.onClick();
              setIsOpen(false);
            }}
            className={`group flex items-center w-full px-4 py-2 text-sm ${
              action.danger
                ? 'text-gray-900 hover:bg-gray-50 font-medium'
                : 'text-gray-700 hover:bg-gray-50'
            } transition-colors duration-150`}
            role="menuitem"
          >
            {action.icon && (
              <span
                className={`mr-3 h-5 w-5 ${action.danger ? 'text-gray-700' : 'text-gray-400 group-hover:text-gray-500'}`}
              >
                {action.icon}
              </span>
            )}
            {action.label}
          </button>
        ))}
      </div>
    </div>
  );

  return (
    <div className="relative inline-block text-left">
      <button
        ref={buttonRef}
        onClick={toggleMenu}
        className="p-2 rounded-full hover:bg-gray-100 transition-colors duration-200 focus:outline-none"
      >
        {/* Vertical Ellipsis SVG */}
        <svg
          xmlns="http://www.w3.org/2000/svg"
          className="h-5 w-5 text-gray-400"
          fill="none"
          viewBox="0 0 24 24"
          stroke="currentColor"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={2}
            d="M12 5v.01M12 12v.01M12 19v.01M12 6a1 1 0 110-2 1 1 0 010 2zm0 7a1 1 0 110-2 1 1 0 010 2zm0 7a1 1 0 110-2 1 1 0 010 2z"
          />
        </svg>
      </button>

      {isOpen && createPortal(menu, document.body)}
    </div>
  );
};

export default ActionMenu;
