import type { Meta, StoryObj } from '@storybook/nextjs-vite'
import ProgressBar from "./ProgressBar";
import { useArgs } from 'storybook/preview-api';

const meta = {
    title: 'Common/ProgressBar',
    component: ProgressBar,
    parameters: {
        layout: 'centered'
    },
    argTypes: {
        value: { control: { type: 'range' } },
        min: { control: { type: 'number' } },
        max: { control: { type: 'number' } }
    }
} satisfies Meta<typeof ProgressBar>;

export default meta;
type Story = StoryObj<typeof meta>

export const Default: Story = {
    render: function Render(args) {
        const [{ value }, updateArgs] = useArgs();

        const onChange = (newValue: number) => {
            updateArgs({ value: newValue });
        };

        return <ProgressBar {...args} value={value} onChange={onChange} min={0} max={100} />
    },
    args: {
        value: 100,
        min: 0,
        max: 100
    }
}

export const StartZero: Story = {
    render: function Render(args) {
        const [{ value }, updateArgs] = useArgs();

        const onChange = (newValue: number) => {
            updateArgs({ value: newValue });
        };

        return <ProgressBar {...args} value={value} onChange={onChange} min={0} max={100} />
    },
    args: {
        value: 0,
        min: 0,
        max: 100
    }
}

export const MiddleFifty: Story = {
    render: function Render(args) {
        const [{ value }, updateArgs] = useArgs();

        const onChange = (newValue: number) => {
            updateArgs({ value: newValue });
        };

        return <ProgressBar {...args} value={value} onChange={onChange} min={0} max={100} />
    },
    args: {
        value: 50,
        min: 0,
        max: 100
    }
}

export const EndHundred: Story = {
    render: function Render(args) {
        const [{ value }, updateArgs] = useArgs();

        const onChange = (newValue: number) => {
            updateArgs({ value: newValue });
        };

        return <ProgressBar {...args} value={value} onChange={onChange} min={0} max={100} />
    },
    args: {
        value: 100,
        min: 0,
        max: 100
    }
}